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
 * The launcher's brain: apps, inventory, cyberware, progression, jobs, the feed and every
 * action the UI can trigger. Views only draw [Game] state and forward taps here.
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
        val stashOpen: Boolean
        val currentTab: Int
        fun invalidateAll()
        fun tick()
        fun openStash(attr: Attr?, pick: Pick?)
        fun closeStash()
        fun stashRefresh()
        fun showMenu(menu: ItemMenu)
        fun hideMenu(): Boolean
        fun showScan(app: AppEntry)
        fun hideScan(): Boolean
        fun showChoice(choice: Choice)
        fun hideChoice(): Boolean
        fun openKeyboard(initial: String)
        fun closeKeyboard()
        fun drop(text: String, color: Int)
        fun banner(b: Banner)
        fun glitch(strength: Float)
        fun selectTab(index: Int)
        fun travel()
        fun breach()
        fun boot(lifepathAfter: Boolean)
        fun themeChanged()
        fun haptic()
    }

    /** What the stash is choosing an app for. */
    sealed class Pick {
        class Slot(val index: Int) : Pick()
        class Implant(val ware: Ware) : Pick()
    }

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

    /** RAM cells spent on quickhacks; recovers over time like the game's RAM. */
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
        val firstRun = !save.initialized
        if (firstRun) firstRun()
        feed(Tag.SYS, "Optics online. Welcome back to Night City, <col=hi>${esc(save.name)}</col>.")
        if (firstRun) {
            for (tip in Content.callTips) feed(Tag.MSG, tip)
        }
        if (coldBoot && (save.boot || save.lifepath < 0)) ui.boot(save.lifepath < 0)
        save.save()
    }

    private fun firstRun() {
        save.initialized = true
        var slot = 0
        for (pkg in actions.defaultPackages()) {
            val app = apps.firstOrNull { it.packageName == pkg && !it.isWork } ?: continue
            if (slot < 12 && save.inventory.none { it == app.key }) save.inventory[slot++] = app.key
        }
        for (app in apps) {
            if (slot >= 8) break
            if (save.inventory.none { it == app.key }) save.inventory[slot++] = app.key
        }
        if (save.jobs.none { it.main }) {
            for (def in Content.mainJobs) save.jobs += Job(def.id, def.title, def.hint, false, true, System.currentTimeMillis())
        }
        save.tracked = "rescue"
    }

    fun resume() {
        resumed = true
        stats.refresh()
        if (actions.isDefaultHome()) mainJob("rescue")
        if (!ticking) {
            ticking = true
            handler.postDelayed(tick, TICK_MS)
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
        cancelInput()
        if (ui.stashOpen) ui.closeStash()
    }

    /** Home pressed while already home: close things, then go back to the inventory. */
    fun homePressed() {
        var acted = ui.hideMenu()
        acted = ui.hideChoice() || acted
        acted = ui.hideScan() || acted
        if (inputMode != InputMode.NONE) {
            cancelInput()
            acted = true
        }
        if (ui.stashOpen) {
            ui.closeStash()
            acted = true
        }
        if (!acted && ui.currentTab != TAB_INVENTORY) {
            ui.selectTab(TAB_INVENTORY)
            acted = true
        }
        if (!acted) ui.glitch(0.6f)
        ui.invalidateAll()
    }

    fun back(): Boolean {
        if (ui.hideChoice() || ui.hideMenu() || ui.hideScan()) return true
        if (inputMode != InputMode.NONE) {
            cancelInput()
            return true
        }
        if (ui.stashOpen) {
            ui.closeStash()
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
            ui.stashRefresh()
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
        if (stats.charging && !wasCharging) feed(Tag.SYS, "Regenerating. Charger connected.")
        if (!stats.charging && lastBattery > LOW_BATTERY && b <= LOW_BATTERY && lastBattery >= 0) {
            feed(Tag.ERR, "<col=red>Health critical.</col> Find a ripperdoc. Or a charger.")
        }
        lastBattery = b
        wasCharging = stats.charging
    }

    // ------------------------------------------------------------------ lookups

    fun app(key: String?): AppEntry? = key?.let { byKey[it] }
    fun invApp(slot: Int): AppEntry? = app(save.inventory.getOrNull(slot))
    fun wareApp(w: Ware): AppEntry? = app(save.cyberware[w.ordinal])
    fun launches(app: AppEntry) = save.launches[app.key] ?: 0
    fun rarity(app: AppEntry) = Rarity.of(launches(app), app.key == topKey)

    fun level() = Curves.level(save.xp)
    fun levelProgress() = Curves.progress(save.xp, level(), Curves::xpForLevel, Curves.MAX_LEVEL)
    fun credLevel() = Curves.credLevel(save.cred)
    fun credProgress() = Curves.progress(save.cred, credLevel(), Curves::credForLevel, Curves.MAX_CRED)
    fun attrLevel(a: Attr) = Curves.attrLevel(save.attrXp[a.ordinal])
    fun attrProgress(a: Attr) = Curves.progress(save.attrXp[a.ordinal], attrLevel(a), Curves::xpForAttr, Curves.ATTR_MAX)
    fun appsTraining(a: Attr) = apps.count { it.attr == a }
    fun legendaries() = apps.count { rarity(it).ordinal >= Rarity.LEGENDARY.ordinal }
    fun ramCells() = stats.ramCells()
    fun ramFree() = max(0f, ramCells() - ramSpent)
    fun unlockedStations() = min(Content.stations.size, 4 + level() / 4)
    fun stationLevel(i: Int) = max(0, (i - 3) * 4)

    fun lifepath(): Lifepath? = Lifepath.values().getOrNull(save.lifepath)

    fun recentApps(n: Int): List<AppEntry> = save.lastUsed.entries.sortedByDescending { it.value }
        .mapNotNull { app(it.key) }.distinctBy { it.key }.take(n)

    fun lastUsed(app: AppEntry): Long = save.lastUsed[app.key] ?: 0L

    private fun recomputeTop() {
        topKey = save.launches.entries.filter { byKey.containsKey(it.key) }.maxByOrNull { it.value }?.key
    }

    fun stashItems(query: String, attr: Attr?): List<AppEntry> {
        val q = query.trim().lowercase()
        var seq = apps.asSequence()
        if (attr != null) seq = seq.filter { it.attr == attr }
        val searching = q.isNotEmpty() && !q.startsWith("/")
        if (searching) seq = seq.filter { it.label.lowercase().contains(q) }
        val list = seq.toList()
        return when {
            searching -> list.sortedWith(compareBy({ rank(it.label.lowercase(), q) }, { it.label.lowercase() }))
            save.stashSort == 1 -> list.sortedWith(compareByDescending<AppEntry> { launches(it) }.thenBy { it.label.lowercase() })
            save.stashSort == 2 -> list.sortedWith(compareByDescending<AppEntry> { lastUsed(it) }.thenBy { it.label.lowercase() })
            else -> list
        }
    }

    private fun rank(label: String, q: String) = when {
        label.startsWith(q) -> 0
        label.split(' ', '-', '.').any { it.startsWith(q) } -> 1
        else -> 2
    }

    // ------------------------------------------------------------------ feed

    fun feed(tag: Tag, text: String) {
        feed.add(FeedLine(tag, text, System.currentTimeMillis()))
        while (feed.size > 120) feed.removeAt(0)
        if (::ui.isInitialized) ui.invalidateAll()
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

        val xp = BASE_XP + if (firstToday) DAILY_BONUS else 0
        award(app.attr, xp)
        addCred(if (before == 0) NEW_ITEM_CRED else LAUNCH_CRED)
        val ed = 8 + Random.nextInt(31) + rarity(app).ordinal * 4
        save.eddies += ed
        ui.drop("+$xp XP  ${app.attr.short}", ROLE_PRIMARY)
        ui.drop("+€$ $ed", ROLE_HI)

        val newRarity = rarity(app)
        if (newRarity != oldRarity) {
            ui.banner(Banner(Banner.Kind.ITEM, "ITEM UPGRADED", app.label.uppercase(Locale.US), newRarity.title.uppercase(Locale.US)))
            feed(Tag.SYS, "${esc(app.label)} is now <col=${hex(newRarity.color)}>${newRarity.title.uppercase(Locale.US)}</col>." +
                if (newRarity.ordinal >= Rarity.LEGENDARY.ordinal) " Maybe touch grass?" else "")
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

    /** Gives XP to the player and to one attribute, announcing any level-ups. */
    fun award(attr: Attr?, amount: Int) {
        val lvBefore = level()
        save.xp += amount
        val lv = level()
        if (lv > lvBefore) {
            ui.banner(Banner(Banner.Kind.LEVEL, "LEVEL UP", "LEVEL $lv", "+1 PERK POINT  (THEY DO NOTHING)"))
            feed(Tag.XP, "<col=hi>Level $lv.</col> ${Content.vTaps[lv % Content.vTaps.size]}")
            ui.glitch(0.8f)
        }
        if (attr != null) {
            val aBefore = attrLevel(attr)
            save.attrXp[attr.ordinal] += amount
            val a = attrLevel(attr)
            if (a > aBefore) {
                ui.banner(Banner(Banner.Kind.ATTR, "${attr.title.uppercase(Locale.US)} INCREASED", "LEVEL $a", attr.skill.uppercase(Locale.US)))
                feed(Tag.XP, "<col=ac>${attr.title} $a.</col> Your ${attr.skill} training is paying off.")
            }
        }
    }

    fun addCred(amount: Int) {
        val before = credLevel()
        save.cred += amount
        val after = credLevel()
        if (after > before) {
            ui.banner(Banner(Banner.Kind.CRED, "STREET CRED INCREASED", "STREET CRED $after", ""))
            feed(Tag.SYS, "Street Cred $after. ${credQuip(after)}")
        }
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

    // ------------------------------------------------------------------ inventory, stash, cyberware

    fun firstFreeSlot(): Int = (0 until SaveData.INVENTORY).firstOrNull { invApp(it) == null } ?: -1

    fun inventoryTap(slot: Int, bounds: Rect?) {
        val app = invApp(slot)
        if (app != null) launch(app, bounds) else pickFor(Pick.Slot(slot))
    }

    fun pickFor(p: Pick) {
        haptic()
        ui.openStash(null, p)
    }

    fun picked(app: AppEntry, p: Pick) {
        when (p) {
            is Pick.Slot -> {
                val existing = save.inventory.indexOf(app.key)
                if (existing >= 0) save.inventory[existing] = save.inventory[p.index]
                save.inventory[p.index] = app.key
                mainJob("pickup")
            }
            is Pick.Implant -> implant(app, p.ware)
        }
        save.save()
        ui.closeStash()
        ui.invalidateAll()
    }

    fun swapInventory(a: Int, b: Int) {
        if (a == b || a !in 0 until SaveData.INVENTORY || b !in 0 until SaveData.INVENTORY) return
        val t = save.inventory[a]
        save.inventory[a] = save.inventory[b]
        save.inventory[b] = t
        save.save()
        ui.invalidateAll()
    }

    fun addToInventory(app: AppEntry) {
        if (save.inventory.any { it == app.key }) {
            feed(Tag.SYS, "${esc(app.label)} is already in your inventory.")
            return
        }
        val free = firstFreeSlot()
        if (free < 0) {
            feed(Tag.ERR, "Inventory full. Drop something first. Weight limit is a law of nature.")
            return
        }
        save.inventory[free] = app.key
        save.save()
        mainJob("pickup")
        feed(Tag.SYS, "Added ${esc(app.label)} to your inventory.")
        ui.invalidateAll()
    }

    fun dropFromInventory(slot: Int) {
        val app = invApp(slot)
        save.inventory[slot] = null
        save.save()
        if (app != null) feed(Tag.SYS, "Dropped ${esc(app.label)}. It's still in your stash.")
        ui.invalidateAll()
    }

    /** Puts an app into a cyberware slot (the first free one, or [into]). */
    fun implant(app: AppEntry, into: Ware? = null) {
        val target = into ?: WARE_ORDER.firstOrNull { wareApp(it) == null } ?: WARE_ORDER[0]
        val old = save.cyberware.indexOf(app.key)
        if (old >= 0) save.cyberware[old] = null
        // Whatever was in the slot goes back into the inventory, ideally where the new one came from.
        val displaced = save.cyberware[target.ordinal]?.takeIf { it != app.key && app(it) != null }
        save.cyberware[target.ordinal] = app.key
        val inv = save.inventory.indexOf(app.key)
        if (inv >= 0) save.inventory[inv] = null
        if (displaced != null && save.inventory.none { it == displaced }) {
            val slot = if (inv >= 0) inv else firstFreeSlot()
            if (slot >= 0) save.inventory[slot] = displaced
        }
        save.save()
        mainJob("chrome")
        feed(Tag.SYS, "Ripperdoc installed ${esc(app.label)} as your <col=ac>${target.title}</col>. No refunds.")
        ui.glitch(0.4f)
        ui.invalidateAll()
    }

    fun removeImplant(w: Ware) {
        val app = wareApp(w)
        save.cyberware[w.ordinal] = null
        if (app != null && save.inventory.none { it == app.key }) {
            val free = firstFreeSlot()
            if (free >= 0) save.inventory[free] = app.key
        }
        save.save()
        if (app != null) feed(Tag.SYS, "Removed ${esc(app.label)} from your ${w.title}. Feels lighter.")
        ui.invalidateAll()
    }

    fun wareTap(w: Ware, bounds: Rect?) {
        val app = wareApp(w)
        if (app != null) launch(app, bounds) else pickFor(Pick.Implant(w))
    }

    private fun subtitle(app: AppEntry): String {
        val n = launches(app)
        return "${rarity(app).title.uppercase(Locale.US)} · ${app.attr.title.uppercase(Locale.US)} · $n USE${if (n == 1) "" else "S"}"
    }

    private fun menu(app: AppEntry, x: Float, y: Float, opts: List<MenuOption>) {
        haptic()
        ui.showMenu(ItemMenu(app.label, subtitle(app), rarity(app).color, opts, x, y))
    }

    fun inventoryMenu(slot: Int, x: Float, y: Float) {
        val app = invApp(slot) ?: return
        menu(app, x, y, listOf(
            MenuOption("Use", "F") { launch(app) },
            MenuOption("Scan", "Q") { scan(app) },
            MenuOption("Install cyberware", "I") { implant(app) },
            MenuOption("Drop", "Z") { dropFromInventory(slot) },
            MenuOption("System info", "C") { source.details(app) },
            MenuOption("Disassemble", "X", danger = true) { disassemble(app) },
        ))
    }

    fun stashMenu(app: AppEntry, x: Float, y: Float) {
        val inInv = save.inventory.any { it == app.key }
        menu(app, x, y, listOfNotNull(
            MenuOption("Use", "F") { launch(app) },
            if (!inInv) MenuOption("Add to inventory", "E") { addToInventory(app) } else null,
            MenuOption("Install cyberware", "I") { implant(app) },
            MenuOption("Scan", "Q") { scan(app) },
            MenuOption("System info", "C") { source.details(app) },
            MenuOption("Disassemble", "X", danger = true) { disassemble(app) },
        ))
    }

    fun wareMenu(w: Ware, x: Float, y: Float) {
        val app = wareApp(w) ?: return
        menu(app, x, y, listOf(
            MenuOption("Use", "F") { launch(app) },
            MenuOption("Scan", "Q") { scan(app) },
            MenuOption("Uninstall cyberware", "R") { removeImplant(w) },
            MenuOption("System info", "C") { source.details(app) },
        ))
    }

    fun scan(app: AppEntry) {
        ui.hideMenu()
        ui.showScan(app)
        mainJob("scan")
    }

    fun scanLine(app: AppEntry): String = Content.intel[Math.floorMod(app.key.hashCode(), Content.intel.size)].replace("%s", app.label)

    fun disassemble(app: AppEntry) {
        ui.showChoice(Choice(
            "RIPPERDOC", "Disassemble ${app.label}? That uninstalls it. No take-backs.",
            listOf(
                ChoiceOption("[Disassemble] Rip it out.", primary = true, danger = true) { source.uninstall(app) },
                ChoiceOption("Never mind, I'm attached to it."),
            ),
        ))
    }

    // ------------------------------------------------------------------ input

    fun beginSearch() {
        inputMode = InputMode.SEARCH
        inputText = ""
        if (!ui.stashOpen) ui.openStash(null, null)
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
        if (inputMode == InputMode.SEARCH) ui.stashRefresh()
        ui.invalidateAll()
    }

    fun submitInput(pick: Pick? = null) {
        val text = inputText.trim()
        val mode = inputMode
        val action = promptAction
        inputMode = InputMode.NONE
        promptAction = null
        ui.closeKeyboard()
        when (mode) {
            InputMode.SEARCH -> when {
                text.startsWith("/") -> {
                    ui.closeStash()
                    command(text)
                }
                text.isEmpty() -> ui.stashRefresh()
                else -> {
                    val hit = stashItems(text, null).firstOrNull()
                    if (hit != null) {
                        if (pick != null) picked(hit, pick) else launch(hit)
                    } else {
                        ui.closeStash()
                        feed(Tag.YOU, esc(text))
                        feed(Tag.SYS, "No match in your stash. Try <col=ac>/net ${esc(text)}</col> to search the net.")
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
        if (wasSearch) ui.stashRefresh()
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ commands

    fun command(raw: String) {
        val parts = raw.removePrefix("/").trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase(Locale.US)
        val arg = parts.getOrElse(1) { "" }.trim()
        feed(Tag.YOU, esc(raw))
        when (cmd) {
            "help", "?", "" -> feed(Tag.SYS, Content.HELP)
            "stash", "inventory", "inv" -> ui.openStash(null, null)
            "scan" -> {
                val hit = if (arg.isEmpty()) null else stashItems(arg, null).firstOrNull()
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
            "motherlode", "rosebud", "money please", "cheat" -> feed(Tag.SYS, "Nice try. Eddies are earned, choom. By opening apps. Like an adult.")
            "travel", "hop", "fasttravel" -> travel()
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
            "settings" -> actions.settings(Settings.ACTION_SETTINGS)
            "quit", "exit" -> quit()
            "preem", "nova", "choom" -> feed(Tag.SYS, "${cmd.replaceFirstChar { it.uppercase() }}.")
            else -> {
                val hit = stashItems(raw.removePrefix("/"), null).firstOrNull()
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
            feed(Tag.SYS, "Palettes: " + Content.palettes.joinToString(", ") {
                it.name + if (it.price > 0 && it.id !in save.owned) " (€$ ${number(it.price.toLong())})" else ""
            })
            return
        }
        vendor(p)
    }

    fun confirmReset() {
        ui.showChoice(Choice(
            "SYSTEM", "Wipe all progress? Level, Street Cred, eddies, attributes and item rarity.",
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
            "SYSTEM", "Quit to the home app picker?",
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

    // ------------------------------------------------------------------ jobs

    fun trackedJob(): Job? {
        save.jobs.firstOrNull { it.id == save.tracked && !it.done }?.let { return it }
        return save.jobs.firstOrNull { it.main && !it.done } ?: save.jobs.firstOrNull { !it.done }
    }

    fun mainJob(id: String) {
        val j = save.jobs.firstOrNull { it.id == id && it.main } ?: return
        if (j.done) return
        j.done = true
        save.eddies += Job.MAIN_EDDIES
        if (save.tracked == id) save.tracked = null
        save.save()
        if (!::ui.isInitialized) return
        ui.banner(Banner(Banner.Kind.JOB, "JOB COMPLETE", j.title.uppercase(Locale.US), "+${Job.MAIN_XP} XP   +€$ ${number(Job.MAIN_EDDIES.toLong())}"))
        feed(Tag.JOB, "<col=hi>${esc(j.title)}</col> complete. +${Job.MAIN_XP} XP, €$ ${number(Job.MAIN_EDDIES.toLong())}.")
        award(null, Job.MAIN_XP)
        addCred(Job.MAIN_CRED)
        ui.invalidateAll()
    }

    fun newGig() = prompt("NAME YOUR GIG") { t ->
        if (t.isNotBlank()) {
            val j = Job("g" + SystemClock.elapsedRealtime(), t.trim().take(48), "", false, false, System.currentTimeMillis())
            save.jobs += j
            save.tracked = j.id
            save.save()
            feed(Tag.JOB, "New gig: <col=hi>${esc(j.title)}</col>. Tracking it.")
        }
    }

    fun jobTap(j: Job) {
        haptic()
        if (j.main) {
            feed(Tag.JOB, "<col=hi>${esc(j.title)}</col>: ${esc(j.note)}")
            if (!j.done) save.tracked = j.id
        } else if (!j.done) {
            j.done = true
            if (save.tracked == j.id) save.tracked = null
            val pay = 250 + Random.nextInt(11) * 50
            save.eddies += pay
            ui.banner(Banner(Banner.Kind.JOB, "GIG COMPLETE", j.title.uppercase(Locale.US), "+${Job.GIG_XP} XP   +€$ $pay"))
            feed(Tag.JOB, "Gig <col=hi>${esc(j.title)}</col> done. The fixer paid €$ $pay. Didn't even haggle.")
            award(null, Job.GIG_XP)
            addCred(Job.GIG_CRED)
            mainJob("gig")
        } else {
            j.done = false
            feed(Tag.JOB, "Gig <col=hi>${esc(j.title)}</col> reopened. The fixer keeps the eddies.")
        }
        save.save()
        ui.invalidateAll()
    }

    fun jobMenu(j: Job, x: Float, y: Float) {
        haptic()
        val opts = ArrayList<MenuOption>()
        if (!j.done) opts += MenuOption("Track", "T") {
            save.tracked = j.id
            save.save()
            ui.invalidateAll()
        }
        if (!j.main) {
            opts += MenuOption("Rename", "R") {
                prompt("RENAME GIG", j.title) { n ->
                    if (n.isNotBlank()) {
                        j.title = n.trim().take(48)
                        save.save()
                    }
                }
            }
            opts += MenuOption("Abandon", "X", danger = true) {
                save.jobs.remove(j)
                if (save.tracked == j.id) save.tracked = null
                save.save()
                feed(Tag.JOB, "Abandoned ${esc(j.title)}. The fixer will remember this.")
            }
        }
        if (opts.isEmpty()) return
        ui.showMenu(ItemMenu(j.title, if (j.main) "MAIN JOB" else "GIG", hiColor(), opts, x, y))
    }

    private fun hiColor() = palette.hi

    // ------------------------------------------------------------------ quickhacks

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
            feed(Tag.ERR, "Quickhack ${q.name} failed. Target ICE too strong. (Nothing could open that setting.)")
            return
        }
        val short = ramFree() < q.ram
        ramSpent = min(ramCells().toFloat(), ramSpent + q.ram)
        save.hacks++
        feed(Tag.SYS, "Uploaded <col=ac>${q.name}</col>." + if (short) " Not enough RAM, but it's your phone, so it went through anyway." else "")
        award(Attr.INT, HACK_XP)
        mainJob("jackin")
        save.save()
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ vendor & lifepath

    fun chooseLifepath(lp: Lifepath, first: Boolean = false) {
        save.lifepath = lp.ordinal
        save.theme = lp.palette
        save.save()
        icons.configure(save.iconStyle, palette)
        ui.themeChanged()
        feed(Tag.SYS, "Lifepath: <col=hi>${lp.title.uppercase(Locale.US)}</col>. ${lp.tagline}")
        if (first) ui.banner(Banner(Banner.Kind.CALL, "INCOMING CALL", "UNKNOWN NUMBER", "CHECK YOUR FEED"))
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
        feed(Tag.SYS, "New look: <col=pr>${p.name}</col>. Preem.")
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
        val s = Content.stations[i]
        feed(Tag.SYS, "Tuned to <col=ac>${s.freq} ${s.name}</col>. Your music app didn't get the memo, but it's playing.")
        mainJob("radio")
        ui.invalidateAll()
    }

    fun mediaKey(code: Int) {
        haptic()
        actions.media(code)
        val n = max(1, unlockedStations())
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> save.station = (save.station + 1) % n
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> save.station = (save.station - 1 + n) % n
        }
        save.save()
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ home screen taps

    fun sceneMenu(x: Float, y: Float) {
        haptic()
        ui.showMenu(ItemMenu("Night City", "${district.first} · ${district.second}", palette.accent, listOfNotNull(
            MenuOption(if (save.rain) "Stop the rain" else "Make it rain", "R") { toggleRain() },
            MenuOption(if (save.scene == 0) "Show my wallpaper" else "Show the city", "V") {
                save.scene = 1 - save.scene
                save.save()
                ui.invalidateAll()
            },
            if (save.scene == 1) MenuOption("Change wallpaper", "W") { actions.wallpaper() } else null,
            MenuOption("Fast travel", "M") { travel() },
            MenuOption("Launcher settings", "O") { ui.selectTab(TAB_SETTINGS) },
            MenuOption("Android settings", "S") { actions.settings(Settings.ACTION_SETTINGS) },
        ), x, y))
    }

    fun catTapped() {
        feed(Tag.MSG, Content.catLines.random())
    }

    fun deviceReport() {
        feed(Tag.SCAN, "Chassis: ${Build.MANUFACTURER} ${Build.MODEL}. Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).")
        feed(Tag.SCAN, String.format(Locale.US, "RAM %.1f of %.1f GB free. Storage %.0f of %.0f GB free. Battery %d%%.",
            stats.ramFreeGb, stats.ramTotalGb, stats.storageFreeGb, stats.storageTotalGb, stats.battery))
    }

    // ------------------------------------------------------------------ previews

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
        var slot = save.inventory.count { it != null }
        for (a in apps) {
            if (slot >= 18) break
            if (save.inventory.none { it == a.key }) save.inventory[slot++] = a.key
        }
        val free = apps.filter { a -> save.inventory.none { it == a.key } }
        WARE_ORDER.take(6).forEachIndexed { i, w -> free.getOrNull(i)?.let { save.cyberware[w.ordinal] = it.key } }
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

        const val TAB_INVENTORY = 0
        const val TAB_CHARACTER = 1
        const val TAB_CYBERWARE = 2
        const val TAB_HACKS = 3
        const val TAB_JOURNAL = 4
        const val TAB_PHONE = 5
        const val TAB_RADIO = 6
        const val TAB_SETTINGS = 7

        /** Colour roles for [Ui.drop]; the view resolves them against the current palette. */
        const val ROLE_PRIMARY = 1
        const val ROLE_ACCENT = 2
        const val ROLE_HI = 3

        val WARE_ORDER = listOf(
            Ware.OS, Ware.CORTEX, Ware.OCULAR, Ware.HANDS, Ware.ARMS,
            Ware.NERVOUS, Ware.CIRCULATORY, Ware.SKIN, Ware.SKELETON, Ware.LEGS,
        )

        fun number(n: Long): String = String.format(Locale.US, "%,d", n)
        fun hex(c: Int) = String.format(Locale.US, "%06x", c and 0xFFFFFF)
    }
}
