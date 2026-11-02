package com.ramong.cyberlauncher.core

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The launcher's brain: apps, the home grid and dock, progression, tasks and every action
 * the UI can trigger. Views only draw [Game] state and forward taps here.
 */
class Game(
    val ctx: Context,
    val source: AppSource,
    val save: SaveData,
    val actions: SystemActions,
    syncIcons: Boolean = false,
) {
    /** What the game needs from the views. Implemented by the root layout. */
    interface Ui {
        val drawerOpen: Boolean
        val page: Int
        fun invalidateAll()
        fun tick()
        fun openDrawer(search: Boolean, pick: Boolean)
        fun closeDrawer()
        fun drawerRefresh()
        fun openSettings()
        fun closeSettings(): Boolean
        fun showMenu(menu: ItemMenu)
        fun hideMenu(): Boolean
        fun showScan(app: AppEntry)
        fun hideScan(): Boolean
        fun showChoice(choice: Choice)
        fun hideChoice(): Boolean
        fun openKeyboard(initial: String)
        fun closeKeyboard()
        fun banner(b: Banner)
        fun toast(line: FeedLine)
        fun glitch(strength: Float)
        fun showPage(page: Int)
        fun travel()
        fun breach()
        fun boot(lifepathAfter: Boolean)
        fun themeChanged()
        fun haptic()
    }

    /** A place an app can sit: one of the home grid cells or one of the dock cells. */
    data class Slot(val dock: Boolean, val index: Int)

    enum class InputMode { NONE, SEARCH, PROMPT }

    lateinit var ui: Ui
    val stats = DeviceStats(ctx)
    val icons = if (syncIcons) {
        IconCache(source, Executor { it.run() }, { it.run() }) { if (::ui.isInitialized) ui.invalidateAll() }
    } else {
        IconCache(source, Executors.newSingleThreadExecutor()) { if (::ui.isInitialized) ui.invalidateAll() }
    }

    var apps: List<AppEntry> = emptyList()
        private set
    private val byKey = HashMap<String, AppEntry>()
    val feed = ArrayList<FeedLine>()
    private var topKey: String? = null

    var inputMode = InputMode.NONE
        private set
    var inputText = ""
        private set
    var promptTitle = ""
        private set
    private var promptAction: ((String) -> Unit)? = null
    /** True while the drawer is open to pick an app for the home screen. */
    var picking = false

    /** RAM cells spent on quick settings; recovers over time like the game's RAM. */
    var ramSpent = 0f
        private set
    private val recentLaunches = ArrayDeque<Long>()
    var heat = 0
        private set
    var district = Content.districts[(System.currentTimeMillis() / 86_400_000L % Content.districts.size).toInt()]
        private set
    var resumed = false
        private set
    private var lastBattery = -1
    private var wasCharging = false
    private var firstHintPending = false

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    var ticks = 0L
        private set
    private val tick = object : Runnable {
        override fun run() {
            ticks++
            onTick()
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    val palette: Palette get() = Content.palette(save.theme)

    // ------------------------------------------------------------------ lifecycle

    fun start(coldBoot: Boolean = true) {
        if (save.installTime == 0L) save.installTime = System.currentTimeMillis()
        if (!save.owned.contains(save.theme) && Content.palette(save.theme).price > 0) save.theme = "street"
        icons.configure(save.iconStyle, palette)
        reloadApps()
        stats.refresh()
        lastBattery = stats.battery
        wasCharging = stats.charging
        if (!save.initialized) firstRun()
        if (!save.dockReady) fillDock()
        if (save.jobs.none { it.main }) {
            for (def in Content.mainJobs) save.jobs += Job(def.id, def.title, def.hint, false, true, System.currentTimeMillis())
        }
        if (coldBoot && (save.boot || save.lifepath < 0)) ui.boot(save.lifepath < 0)
        save.save()
    }

    private fun firstRun() {
        save.initialized = true
        firstHintPending = true
        fillDock()
        // The rest of the usual suspects go on the home screen, then anything else up to a row of eight.
        var slot = 0
        for (pkg in actions.defaultPackages()) {
            val app = apps.firstOrNull { it.packageName == pkg && !it.isWork } ?: continue
            if (slot < 8 && save.dock.none { it == app.key } && save.home.none { it == app.key }) save.home[slot++] = app.key
        }
        for (app in apps) {
            if (slot >= 8) break
            if (save.dock.none { it == app.key } && save.home.none { it == app.key }) save.home[slot++] = app.key
        }
    }

    /** Phone, messages, browser, camera and one more in the dock, like every other launcher. */
    private fun fillDock() {
        save.dockReady = true
        if (save.dock.any { app(it) != null }) return
        var d = 0
        for (pkg in actions.defaultPackages()) {
            if (d >= SaveData.DOCK) break
            val app = apps.firstOrNull { it.packageName == pkg && !it.isWork } ?: continue
            if (save.dock.none { it == app.key }) save.dock[d++] = app.key
        }
        for (app in apps) {
            if (d >= SaveData.DOCK) break
            if (save.dock.none { it == app.key }) save.dock[d++] = app.key
        }
    }

    fun resume() {
        resumed = true
        stats.refresh()
        if (actions.isDefaultHome()) mainJob("rescue")
        if (!ticking) {
            ticking = true
            handler.postDelayed(tick, TICK_MS)
        }
        if (firstHintPending && save.lifepath >= 0) {
            firstHintPending = false
            feed(Tag.MSG, Content.FIRST_HINT)
        }
        ui.invalidateAll()
    }

    fun pause() {
        resumed = false
        ticking = false
        handler.removeCallbacks(tick)
        save.save()
    }

    /** The launcher went to the background: tidy up so it comes back to the home screen. */
    fun hidden() {
        ui.hideMenu()
        ui.hideChoice()
        ui.hideScan()
        ui.closeSettings()
        cancelInput()
        if (ui.drawerOpen) ui.closeDrawer()
    }

    /** Home pressed while already home: close things, then go back to the home page. */
    fun homePressed() {
        var acted = ui.hideMenu()
        acted = ui.hideChoice() || acted
        acted = ui.hideScan() || acted
        acted = ui.closeSettings() || acted
        if (inputMode != InputMode.NONE) {
            cancelInput()
            acted = true
        }
        if (ui.drawerOpen) {
            ui.closeDrawer()
            acted = true
        }
        if (!acted && ui.page != PAGE_HOME) {
            ui.showPage(PAGE_HOME)
            acted = true
        }
        if (!acted) ui.glitch(0.6f)
        ui.invalidateAll()
    }

    fun back(): Boolean {
        if (ui.hideChoice() || ui.hideMenu() || ui.hideScan() || ui.closeSettings()) return true
        if (inputMode != InputMode.NONE) {
            cancelInput()
            return true
        }
        if (ui.drawerOpen) {
            ui.closeDrawer()
            return true
        }
        if (ui.page != PAGE_HOME) {
            ui.showPage(PAGE_HOME)
            return true
        }
        return false
    }

    fun reloadApps() {
        apps = source.load()
        byKey.clear()
        apps.forEach { byKey[it.key] = it }
        recomputeTop()
        if (::ui.isInitialized) {
            ui.drawerRefresh()
            ui.invalidateAll()
        }
    }

    private fun onTick() {
        if (ticks % 5 == 0L) {
            stats.refresh()
            batteryEvents()
        }
        if (ramSpent > 0f) ramSpent = max(0f, ramSpent - RAM_RECOVERY)
        updateHeat(announce = false)
        ui.tick()
    }

    fun onBatteryChanged() {
        batteryEvents()
        if (::ui.isInitialized) ui.invalidateAll()
    }

    private fun batteryEvents() {
        val b = stats.battery
        if (!stats.charging && lastBattery > LOW_BATTERY && b <= LOW_BATTERY && lastBattery >= 0) {
            feed(Tag.ERR, "<col=red>Health critical.</col> Find a ripperdoc. Or a charger.")
        }
        lastBattery = b
        wasCharging = stats.charging
    }

    // ------------------------------------------------------------------ lookups

    fun app(key: String?): AppEntry? = key?.let { byKey[it] }
    fun homeApp(i: Int): AppEntry? = app(save.home.getOrNull(i))
    fun dockApp(i: Int): AppEntry? = app(save.dock.getOrNull(i))
    fun slotApp(s: Slot): AppEntry? = if (s.dock) dockApp(s.index) else homeApp(s.index)
    fun launches(app: AppEntry) = save.launches[app.key] ?: 0
    fun rarity(app: AppEntry) = Rarity.of(launches(app), app.key == topKey)
    fun onHome(app: AppEntry) = save.home.any { it == app.key }
    fun inDock(app: AppEntry) = save.dock.any { it == app.key }

    fun level() = Curves.level(save.xp)
    fun levelProgress() = Curves.progress(save.xp, level(), Curves::xpForLevel, Curves.MAX_LEVEL)
    fun credLevel() = Curves.credLevel(save.cred)
    fun credProgress() = Curves.progress(save.cred, credLevel(), Curves::credForLevel, Curves.MAX_CRED)
    fun attrLevel(a: Attr) = Curves.attrLevel(save.attrXp[a.ordinal])
    fun attrProgress(a: Attr) = Curves.progress(save.attrXp[a.ordinal], attrLevel(a), Curves::xpForAttr, Curves.ATTR_MAX)
    fun legendaries() = apps.count { rarity(it).ordinal >= Rarity.LEGENDARY.ordinal }
    fun ramCells() = stats.ramCells()
    fun ramFree() = max(0f, ramCells() - ramSpent)
    fun unlockedStations() = min(Content.stations.size, 4 + level() / 4)
    fun stationLevel(i: Int) = max(0, (i - 3) * 4)

    fun lifepath(): Lifepath? = Lifepath.values().getOrNull(save.lifepath)

    fun recentApps(n: Int): List<AppEntry> = save.lastUsed.entries.sortedByDescending { it.value }
        .mapNotNull { app(it.key) }.distinctBy { it.key }.take(n)

    fun lastUsed(app: AppEntry): Long = save.lastUsed[app.key] ?: 0L

    /** The user's own to-do list. Built-in jobs are hidden achievements. */
    fun tasks(): List<Job> = save.jobs.filter { !it.main }.sortedBy { it.done }
    fun nextTask(): Job? = save.jobs.firstOrNull { !it.main && !it.done }

    private fun recomputeTop() {
        topKey = save.launches.entries.filter { byKey.containsKey(it.key) }.maxByOrNull { it.value }?.key
    }

    /** All apps A to Z, or the best matches first while searching. */
    fun drawerItems(query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty() || q.startsWith("/")) return apps
        return apps.filter { it.label.lowercase().contains(q) }
            .sortedWith(compareBy({ rank(it.label.lowercase(), q) }, { it.label.lowercase() }))
    }

    private fun rank(label: String, q: String) = when {
        label.startsWith(q) -> 0
        label.split(' ', '-', '.').any { it.startsWith(q) } -> 1
        else -> 2
    }

    // ------------------------------------------------------------------ messages

    /** A short message for the user, shown as a toast over the dock. */
    fun feed(tag: Tag, text: String) {
        val line = FeedLine(tag, text, System.currentTimeMillis())
        feed.add(line)
        while (feed.size > 60) feed.removeAt(0)
        if (::ui.isInitialized) ui.toast(line)
    }

    fun esc(s: String) = s.replace('<', '‹').replace('>', '›')

    // ------------------------------------------------------------------ launching & rewards

    fun launch(app: AppEntry, bounds: Rect? = null, options: Bundle? = null) {
        haptic()
        if (!source.launch(app, bounds, options)) {
            feed(Tag.ERR, "Connection refused. ${esc(app.label)} won't open.")
            ui.glitch(0.5f)
            return
        }
        val now = System.currentTimeMillis()
        val before = launches(app)
        val oldRarity = rarity(app)
        val firstToday = !sameDay(save.lastUsed[app.key] ?: 0L, now)
        save.launches[app.key] = before + 1
        save.lastUsed[app.key] = now
        save.totalLaunches++
        recomputeTop()

        award(app.attr, BASE_XP + if (firstToday) DAILY_BONUS else 0)
        addCred(if (before == 0) NEW_ITEM_CRED else LAUNCH_CRED)
        save.eddies += 8 + Random.nextInt(31) + rarity(app).ordinal * 4

        val newRarity = rarity(app)
        if (newRarity != oldRarity) {
            ui.banner(Banner(Banner.Kind.ITEM, "ITEM UPGRADED", app.label.uppercase(Locale.US), newRarity.title.uppercase(Locale.US)))
            if (newRarity.ordinal >= Rarity.LEGENDARY.ordinal) mainJob("legend")
        }
        recentLaunches.addLast(now)
        updateHeat(announce = true)
        if (!save.hasCat && Random.nextInt(CAT_CHANCE) == 0) {
            save.hasCat = true
            feed(Tag.MSG, "<col=ac>A stray cat has decided you're its problem now.</col>")
        }
        save.save()
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        if (a == 0L) return false
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** Gives XP to the player and to one attribute, announcing level-ups. */
    fun award(attr: Attr?, amount: Int) {
        val lvBefore = level()
        save.xp += amount
        val lv = level()
        if (lv > lvBefore) {
            ui.banner(Banner(Banner.Kind.LEVEL, "LEVEL UP", "LEVEL $lv", "+1 PERK POINT  (THEY DO NOTHING)"))
            ui.glitch(0.8f)
        }
        if (attr != null) {
            val aBefore = attrLevel(attr)
            save.attrXp[attr.ordinal] += amount
            if (attrLevel(attr) > aBefore) feed(Tag.XP, "<col=ac>${attr.title} ${attrLevel(attr)}.</col> Your ${attr.skill} training is paying off.")
        }
    }

    fun addCred(amount: Int) {
        val before = credLevel()
        save.cred += amount
        val after = credLevel()
        if (after > before) feed(Tag.SYS, "<col=hi>Street Cred $after.</col> ${credQuip(after)}")
    }

    private fun credQuip(level: Int) = when {
        level >= 50 -> "Night City knows your name. Mostly to complain about it."
        level >= 30 -> "Fixers return your calls now. Eventually."
        level >= 15 -> "The vending machines are starting to recognise you."
        level >= 5 -> "Somebody somewhere has heard of you."
        else -> "Baby steps."
    }

    private fun updateHeat(announce: Boolean) {
        val cutoff = System.currentTimeMillis() - HEAT_WINDOW_MS
        while (recentLaunches.isNotEmpty() && recentLaunches.first() < cutoff) recentLaunches.removeFirst()
        val n = recentLaunches.size
        val h = HEAT_STEPS.count { n >= it }
        if (h != heat) {
            if (announce && h > heat) {
                val msg = when (h) {
                    1 -> "You're drawing attention. $n apps in 15 minutes."
                    2 -> "NCPD is circling. Maybe put the phone down, choom."
                    3 -> "Max-Tac has been notified about your screen time."
                    4 -> "Helicopters. For your screen time. Impressive."
                    else -> "Full heat. The whole city is watching you doomscroll."
                }
                feed(Tag.NCPD, "<col=red>${"★".repeat(h)}</col> $msg")
            }
            heat = h
            if (::ui.isInitialized) ui.invalidateAll()
        }
    }

    fun haptic() {
        if (save.haptics && ::ui.isInitialized) ui.haptic()
    }

    // ------------------------------------------------------------------ home screen and dock

    fun firstFreeHome(): Int = (0 until SaveData.HOME).firstOrNull { homeApp(it) == null } ?: -1
    fun firstFreeDock(): Int = (0 until SaveData.DOCK).firstOrNull { dockApp(it) == null } ?: -1

    private fun cells(s: Slot) = if (s.dock) save.dock else save.home

    fun addToHome(app: AppEntry): Boolean {
        if (onHome(app)) return true
        val free = firstFreeHome()
        if (free < 0) {
            feed(Tag.ERR, "Home screen is full. Drag something off it first.")
            return false
        }
        save.home[free] = app.key
        save.save()
        mainJob("pickup")
        ui.invalidateAll()
        return true
    }

    fun addToDock(app: AppEntry): Boolean {
        if (inDock(app)) return true
        val free = firstFreeDock()
        if (free < 0) {
            feed(Tag.ERR, "The dock is full. Drag something out of it first.")
            return false
        }
        save.dock[free] = app.key
        save.save()
        mainJob("chrome")
        ui.invalidateAll()
        return true
    }

    fun removeFrom(s: Slot) {
        cells(s)[s.index] = null
        save.save()
        ui.invalidateAll()
    }

    /** Drag and drop between home cells and dock cells. Whatever is at [to] swaps into [from]. */
    fun move(from: Slot, to: Slot) {
        if (from == to) return
        val a = cells(from)
        val b = cells(to)
        val t = b[to.index]
        b[to.index] = a[from.index]
        a[from.index] = t
        save.save()
        if (to.dock) mainJob("chrome")
        ui.invalidateAll()
    }

    /** An app dragged in from the drawer. Anything already there moves to the next free cell. */
    fun place(app: AppEntry, to: Slot) {
        val arr = cells(to)
        val existing = arr.indexOf(app.key)
        if (existing >= 0) arr[existing] = null
        val occupant = arr[to.index]?.takeIf { it != app.key && app(it) != null }
        arr[to.index] = app.key
        if (occupant != null) {
            val free = if (to.dock) firstFreeDock() else firstFreeHome()
            if (free >= 0) arr[free] = occupant
        }
        save.save()
        mainJob(if (to.dock) "chrome" else "pickup")
        ui.invalidateAll()
    }

    fun picked(app: AppEntry) {
        picking = false
        addToHome(app)
        ui.closeDrawer()
    }

    private fun subtitle(app: AppEntry): String {
        val n = launches(app)
        return "${rarity(app).title.uppercase(Locale.US)} · ${app.attr.title.uppercase(Locale.US)} · $n LAUNCH${if (n == 1) "" else "ES"}"
    }

    /** Long-press menu for an app. [slot] says where it was held, or null for the drawer and deck. */
    fun appMenu(app: AppEntry, slot: Slot?, x: Float, y: Float) {
        haptic()
        val opts = ArrayList<MenuOption>()
        when {
            slot == null -> {
                if (!onHome(app)) opts += MenuOption("Add to home") { addToHome(app) }
                if (!inDock(app)) opts += MenuOption("Add to dock") { addToDock(app) }
            }
            slot.dock -> opts += MenuOption("Remove from dock") { removeFrom(slot) }
            else -> {
                opts += MenuOption("Remove from home") { removeFrom(slot) }
                if (!inDock(app)) opts += MenuOption("Move to dock") {
                    if (addToDock(app)) removeFrom(slot)
                }
            }
        }
        opts += MenuOption("Scan app") { scan(app) }
        opts += MenuOption("App info") { source.details(app) }
        opts += MenuOption("Uninstall", danger = true) { uninstall(app) }
        ui.showMenu(ItemMenu(app.label, subtitle(app), rarity(app).color, opts, x, y))
    }

    /** Long-press on empty space. */
    fun homeMenu(x: Float, y: Float) {
        haptic()
        ui.showMenu(ItemMenu("Home screen", "${district.first} · ${district.second}", palette.accent, listOf(
            MenuOption("Add apps") {
                picking = true
                ui.openDrawer(search = false, pick = true)
            },
            MenuOption("Launcher settings") { ui.openSettings() },
            MenuOption(if (save.rain) "Stop the rain" else "Make it rain") { toggleRain() },
            MenuOption("New skyline") { travel() },
            MenuOption("Android settings") { actions.settings(Settings.ACTION_SETTINGS) },
        ), x, y))
    }

    fun scan(app: AppEntry) {
        ui.hideMenu()
        ui.showScan(app)
        mainJob("scan")
    }

    fun scanLine(app: AppEntry): String = Content.intel[Math.floorMod(app.key.hashCode(), Content.intel.size)].replace("%s", app.label)

    fun uninstall(app: AppEntry) {
        ui.showChoice(Choice(
            "RIPPERDOC", "Uninstall ${app.label}? It's gone for good.",
            listOf(
                ChoiceOption("[Uninstall] Rip it out.", primary = true, danger = true) { source.uninstall(app) },
                ChoiceOption("Never mind, I'm attached to it."),
            ),
        ))
    }

    // ------------------------------------------------------------------ input

    fun beginSearch() {
        inputMode = InputMode.SEARCH
        inputText = ""
        if (!ui.drawerOpen) ui.openDrawer(search = true, pick = picking)
        ui.openKeyboard("")
        ui.invalidateAll()
    }

    fun prompt(title: String, initial: String = "", action: (String) -> Unit) {
        inputMode = InputMode.PROMPT
        promptTitle = title
        promptAction = action
        inputText = initial
        ui.openKeyboard(initial)
        ui.invalidateAll()
    }

    fun onInputChanged(t: String) {
        if (inputMode == InputMode.NONE) return
        inputText = t.take(60)
        if (inputMode == InputMode.SEARCH) ui.drawerRefresh()
        ui.invalidateAll()
    }

    fun submitInput() {
        val text = inputText.trim()
        val mode = inputMode
        val action = promptAction
        inputMode = InputMode.NONE
        promptAction = null
        ui.closeKeyboard()
        when (mode) {
            InputMode.SEARCH -> when {
                text.startsWith("/") -> {
                    ui.closeDrawer()
                    command(text)
                }
                text.isEmpty() -> ui.drawerRefresh()
                else -> {
                    val hit = drawerItems(text).firstOrNull()
                    when {
                        hit != null && picking -> picked(hit)
                        hit != null -> launch(hit)
                        else -> {
                            ui.closeDrawer()
                            actions.webSearch(text)
                        }
                    }
                }
            }
            InputMode.PROMPT -> action?.invoke(text)
            InputMode.NONE -> Unit
        }
        ui.invalidateAll()
    }

    fun cancelInput() {
        if (inputMode == InputMode.NONE) return
        val wasSearch = inputMode == InputMode.SEARCH
        inputMode = InputMode.NONE
        inputText = ""
        promptAction = null
        ui.closeKeyboard()
        if (wasSearch) ui.drawerRefresh()
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ commands

    fun command(raw: String) {
        val parts = raw.removePrefix("/").trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase(Locale.US)
        val arg = parts.getOrElse(1) { "" }.trim()
        when (cmd) {
            "help", "?", "" -> feed(Tag.SYS, Content.HELP)
            "apps", "stash", "drawer" -> ui.openDrawer(search = false, pick = false)
            "scan" -> {
                val hit = if (arg.isEmpty()) null else drawerItems(arg).firstOrNull()
                if (hit != null) scan(hit) else feed(Tag.ERR, "Scan what? Try /scan spotify.")
            }
            "net", "search", "google" -> if (arg.isNotEmpty()) actions.webSearch(arg) else feed(Tag.ERR, "Search the net for what, exactly?")
            "name", "handle" -> if (arg.isNotEmpty()) setName(arg) else askName()
            "theme", "palette" -> themeCommand(arg)
            "stats", "xp", "me" -> feed(Tag.SYS,
                "Level ${level()}. Street Cred ${credLevel()}. €$ ${number(save.eddies)}. " +
                    Attr.values().joinToString(" ") { "${it.short} ${attrLevel(it)}" } + ". ${number(save.totalLaunches)} launches.")
            "heat", "wanted" -> feed(Tag.NCPD, if (heat == 0) "No heat. The NCPD has no idea who you are." else "Heat level $heat. ${recentLaunches.size} apps in 15 minutes.")
            "eddies", "money", "balance" -> feed(Tag.EDDIES, "€$ ${number(save.eddies)}. Open apps to earn more. That's the whole economy.")
            "motherlode", "rosebud", "cheat" -> feed(Tag.SYS, "Nice try. Eddies are earned, choom. By opening apps. Like an adult.")
            "travel", "hop", "skyline" -> travel()
            "rain" -> toggleRain()
            "glitch" -> ui.glitch(1f)
            "breach" -> ui.breach()
            "flatline" -> {
                ui.glitch(1.4f)
                feed(Tag.ERR, "You flatlined. Respawning... done. Nobody saw that.")
            }
            "wake", "wakeup" -> feed(Tag.SYS, "Already awake. Unfortunately.")
            "sudo", "root" -> feed(Tag.ERR, "Permission denied. ${sudoQuip()}")
            "cat", "pet" -> feed(Tag.MSG, if (save.hasCat) Content.catLines.random() else "No cat. Yet. Keep opening apps.")
            "reset" -> confirmReset()
            "home" -> actions.homeSettings()
            "wallpaper" -> actions.wallpaper()
            "settings", "options" -> ui.openSettings()
            "deck" -> ui.showPage(PAGE_DECK)
            "quit", "exit" -> quit()
            "preem", "nova", "choom" -> feed(Tag.SYS, "${cmd.replaceFirstChar { it.uppercase() }}.")
            else -> {
                val hit = drawerItems(raw.removePrefix("/")).firstOrNull()
                if (hit != null) launch(hit) else feed(Tag.ERR, "Unknown command: /$cmd. Type /help.")
            }
        }
    }

    private fun sudoQuip() = when (lifepath()) {
        Lifepath.CORPO -> "Your corporate access was revoked. Again."
        Lifepath.NOMAD -> "Nomads don't do root. They do roads."
        else -> "You're not root, you're a street kid."
    }

    fun askName() = prompt("ENTER YOUR HANDLE", save.name) { setName(it) }

    fun setName(n: String) {
        val name = n.trim().take(14)
        if (name.isEmpty()) return
        save.name = name
        save.save()
        feed(Tag.SYS, "Handle updated. The city now calls you <col=hi>${esc(name)}</col>.")
    }

    private fun themeCommand(arg: String) {
        val p = Content.palettes.firstOrNull { it.name.equals(arg, true) || it.id.equals(arg, true) }
        if (p == null) {
            feed(Tag.SYS, "Themes: " + Content.palettes.joinToString(", ") {
                it.name + if (it.price > 0 && it.id !in save.owned) " (€$ ${number(it.price.toLong())})" else ""
            })
            return
        }
        vendor(p)
    }

    fun confirmReset() {
        ui.showChoice(Choice(
            "SYSTEM", "Wipe all progress? Level, Street Cred, eddies, attributes and app rarity. Your home screen stays.",
            listOf(
                ChoiceOption("[Wipe] Start over.", primary = true, danger = true) {
                    save.xp = 0L
                    save.cred = 0L
                    save.eddies = 0L
                    save.attrXp.fill(0L)
                    save.launches.clear()
                    save.totalLaunches = 0L
                    recomputeTop()
                    save.save()
                    feed(Tag.SYS, "Memory wiped. Welcome to Night City. Again.")
                    ui.glitch(1f)
                    ui.invalidateAll()
                },
                ChoiceOption("Keep my stuff."),
            ),
        ))
    }

    fun quit() {
        ui.showChoice(Choice(
            "SYSTEM", "Switch to a different home app?",
            listOf(
                ChoiceOption("[Quit] Pick another home app.", primary = true) {
                    mainJob("quit")
                    actions.homeSettings()
                },
                ChoiceOption("Keep playing."),
            ),
        ))
    }

    fun travel() {
        district = Content.districts.random()
        ui.travel()
        feed(Tag.SYS, "Fast travel to <col=ac>${district.first}</col> · ${district.second}.")
    }

    fun toggleRain() {
        save.rain = !save.rain
        save.save()
        feed(Tag.SYS, if (save.rain) "It's raining again. It's always raining." else "Rain off. Weather control is a corpo luxury.")
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ jobs and tasks

    /** Built-in jobs are achievements: the first time you do the thing, it pays out. */
    fun mainJob(id: String) {
        val j = save.jobs.firstOrNull { it.id == id && it.main } ?: return
        if (j.done) return
        j.done = true
        save.eddies += Job.MAIN_EDDIES
        save.save()
        if (!::ui.isInitialized) return
        ui.banner(Banner(Banner.Kind.JOB, "JOB COMPLETE", j.title.uppercase(Locale.US), "+${Job.MAIN_XP} XP   +€$ ${number(Job.MAIN_EDDIES.toLong())}"))
        award(null, Job.MAIN_XP)
        addCred(Job.MAIN_CRED)
        ui.invalidateAll()
    }

    fun jobsDone() = save.jobs.count { it.main && it.done }
    fun jobsTotal() = save.jobs.count { it.main }

    fun newTask() = prompt("NEW TASK") { t ->
        if (t.isNotBlank()) {
            save.jobs += Job("g" + SystemClock.elapsedRealtime(), t.trim().take(48), "", false, false, System.currentTimeMillis())
            save.save()
            ui.invalidateAll()
        }
    }

    fun taskTap(j: Job) {
        haptic()
        if (!j.done) {
            j.done = true
            val pay = 250 + Random.nextInt(11) * 50
            save.eddies += pay
            ui.banner(Banner(Banner.Kind.JOB, "GIG COMPLETE", j.title.uppercase(Locale.US), "+${Job.GIG_XP} XP   +€$ $pay"))
            award(null, Job.GIG_XP)
            addCred(Job.GIG_CRED)
            mainJob("gig")
        } else {
            j.done = false
        }
        save.save()
        ui.invalidateAll()
    }

    fun taskMenu(j: Job, x: Float, y: Float) {
        haptic()
        ui.showMenu(ItemMenu(j.title, if (j.done) "DONE" else "TASK", palette.hi, listOf(
            MenuOption(if (j.done) "Mark not done" else "Mark done") { taskTap(j) },
            MenuOption("Rename") {
                prompt("RENAME TASK", j.title) { n ->
                    if (n.isNotBlank()) {
                        j.title = n.trim().take(48)
                        save.save()
                    }
                }
            },
            MenuOption("Delete", danger = true) {
                save.jobs.remove(j)
                save.save()
                ui.invalidateAll()
            },
        ), x, y))
    }

    fun clearDoneTasks() {
        save.jobs.removeAll { !it.main && it.done }
        save.save()
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ quick settings

    fun quickhack(q: QuickhackDef) {
        haptic()
        val ok = when (q.id) {
            "ping" -> actions.internet()
            "optics" -> {
                if (!actions.hasTorch()) {
                    feed(Tag.ERR, "No optics to reboot. This phone has no flashlight.")
                    return
                }
                actions.toggleTorch()
            }
            "sonic" -> {
                actions.volume()
                true
            }
            "wipe" -> actions.doNotDisturb()
            "cripple" -> actions.location()
            "malfunction" -> actions.bluetooth()
            "overheat" -> actions.batterySaver()
            "backup" -> actions.hotspot()
            "reset" -> actions.airplane()
            else -> actions.settings(Settings.ACTION_SETTINGS)
        }
        if (!ok) {
            feed(Tag.ERR, "${q.label} failed. Target ICE too strong. (Nothing could open that setting.)")
            return
        }
        ramSpent = min(ramCells().toFloat(), ramSpent + q.ram)
        save.hacks++
        award(Attr.INT, HACK_XP)
        mainJob("jackin")
        save.save()
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ themes & lifepath

    fun chooseLifepath(lp: Lifepath, first: Boolean = false) {
        save.lifepath = lp.ordinal
        save.theme = lp.palette
        save.save()
        icons.configure(save.iconStyle, palette)
        ui.themeChanged()
        if (first) {
            ui.banner(Banner(Banner.Kind.CALL, "LIFEPATH: ${lp.title.uppercase(Locale.US)}", lp.tagline.uppercase(Locale.US), ""))
            if (firstHintPending) {
                firstHintPending = false
                feed(Tag.MSG, Content.FIRST_HINT)
            }
        }
        ui.invalidateAll()
    }

    fun vendor(p: Palette) {
        haptic()
        when {
            p.price == 0 || p.id in save.owned -> applyPalette(p)
            save.eddies >= p.price -> ui.showChoice(Choice(
                "VENDOR", "${p.name}: ${p.tagline} Yours for €$ ${number(p.price.toLong())}.",
                listOf(
                    ChoiceOption("[Pay €$ ${number(p.price.toLong())}] Deal.", primary = true) {
                        save.eddies -= p.price
                        save.owned += p.id
                        save.save()
                        ui.banner(Banner(Banner.Kind.VENDOR, "PURCHASED", p.name.uppercase(Locale.US), "-€$ ${number(p.price.toLong())}"))
                        mainJob("vendor")
                        applyPalette(p)
                    },
                    ChoiceOption("Just browsing."),
                ),
            ))
            else -> feed(Tag.EDDIES, "${p.name} costs €$ ${number(p.price.toLong())}. You're €$ ${number(p.price - save.eddies)} short. Go open some apps.")
        }
    }

    private fun applyPalette(p: Palette) {
        if (save.theme == p.id) return
        save.theme = p.id
        save.save()
        icons.configure(save.iconStyle, p)
        ui.themeChanged()
        ui.glitch(0.7f)
    }

    fun setIconStyle(style: Int) {
        save.iconStyle = style
        save.save()
        icons.configure(style, palette)
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ radio

    fun tune(i: Int) {
        haptic()
        if (i >= unlockedStations()) {
            feed(Tag.SYS, "Station locked. Reach level ${stationLevel(i)} to pick up ${Content.stations[i].name}.")
            return
        }
        save.station = i
        save.save()
        actions.media(KeyEvent.KEYCODE_MEDIA_PLAY)
        mainJob("radio")
        ui.invalidateAll()
    }

    fun nextStation(step: Int) {
        val n = max(1, unlockedStations())
        save.station = Math.floorMod(save.station + step, n)
        save.save()
        mainJob("radio")
        ui.invalidateAll()
    }

    fun mediaKey(code: Int) {
        haptic()
        actions.media(code)
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ odds and ends

    fun catTapped() {
        feed(Tag.MSG, Content.catLines.random())
    }

    fun deviceReport() {
        feed(Tag.SCAN, "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}. " + String.format(Locale.US,
            "RAM %.1f of %.1f GB free, storage %.0f of %.0f GB free.", stats.ramFreeGb, stats.ramTotalGb, stats.storageFreeGb, stats.storageTotalGb))
    }

    /** Fills in a lived-in save for screenshots. Only reachable from debug builds and tests. */
    fun demoSeed() {
        val r = Random(2077)
        apps.forEachIndexed { i, a ->
            val n = when {
                i % 11 == 0 -> 260 + r.nextInt(200)
                i % 5 == 0 -> 80 + r.nextInt(100)
                i % 3 == 0 -> 26 + r.nextInt(40)
                i % 2 == 0 -> 5 + r.nextInt(15)
                else -> r.nextInt(5)
            }
            if (n > 0) {
                save.launches[a.key] = n
                save.lastUsed[a.key] = System.currentTimeMillis() - r.nextInt(3 * 86_400) * 1000L
            }
        }
        save.totalLaunches = save.launches.values.sum().toLong()
        save.xp = Curves.xpForLevel(23) + 4_000
        save.cred = Curves.credForLevel(17) + 300
        save.eddies = 18_420
        val lv = intArrayOf(9, 14, 11, 16, 7)
        for (a in Attr.values()) save.attrXp[a.ordinal] = Curves.xpForAttr(lv[a.ordinal]) + 500
        save.owned += "samurai"
        save.hasCat = true
        save.jobs.filter { it.main }.take(4).forEach { it.done = true }
        if (save.jobs.none { !it.main }) {
            save.jobs += Job("g1", "Pick up groceries", "", false, false, System.currentTimeMillis())
            save.jobs += Job("g2", "Call mom back", "", false, false, System.currentTimeMillis())
            save.jobs += Job("g3", "Pay the rent", "", true, false, System.currentTimeMillis())
        }
        var slot = save.home.count { it != null }
        for (a in apps) {
            if (slot >= 16) break
            if (save.home.none { it == a.key } && save.dock.none { it == a.key }) save.home[slot++] = a.key
        }
        recomputeTop()
        save.save()
        if (::ui.isInitialized) ui.invalidateAll()
    }

    companion object {
        const val TICK_MS = 1000L
        const val CAT_CHANCE = 1000
        const val BASE_XP = 40
        const val DAILY_BONUS = 20
        const val HACK_XP = 15
        const val LAUNCH_CRED = 8
        const val NEW_ITEM_CRED = 60
        const val LOW_BATTERY = 15
        const val RAM_RECOVERY = 0.25f
        const val HEAT_WINDOW_MS = 15 * 60_000L
        val HEAT_STEPS = intArrayOf(8, 14, 20, 28, 36)

        const val PAGE_DECK = 0
        const val PAGE_HOME = 1

        fun number(n: Long): String = String.format(Locale.US, "%,d", n)
        fun hex(c: Int) = String.format(Locale.US, "%06x", c and 0xFFFFFF)
    }
}
