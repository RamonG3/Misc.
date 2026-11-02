package com.ramong.runelauncher.core

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The launcher's brain: apps, the home grid, dock and folders, skills and XP, quests and every
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
        val drawerOpen: Boolean
        val page: Int
        fun invalidateAll()
        fun tick()
        fun openDrawer(search: Boolean, pick: Boolean)
        fun closeDrawer()
        fun drawerRefresh()
        fun openSettings()
        fun closeSettings(): Boolean
        fun openFolder(id: String)
        fun closeFolder(): Boolean
        fun showMenu(menu: ItemMenu)
        fun hideMenu(): Boolean
        fun showExamine(app: AppEntry)
        fun hideExamine(): Boolean
        fun showChoice(choice: Choice)
        fun hideChoice(): Boolean
        fun openKeyboard(initial: String)
        fun closeKeyboard()
        fun banner(b: Banner)
        fun toast(line: FeedLine)
        fun xpDrop(skill: Skill, amount: Int)
        fun showPage(page: Int)
        fun worldHop()
        fun themeChanged()
        fun haptic()
    }

    /** A place something can sit: one of the home grid cells or one of the dock cells. */
    data class Slot(val dock: Boolean, val index: Int)

    /** What a cell holds, resolved. Cells pointing at uninstalled apps read as empty. */
    sealed class Cell {
        class App(val app: AppEntry) : Cell()
        class Dir(val folder: Folder) : Cell()
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

    var inputMode = InputMode.NONE
        private set
    var inputText = ""
        private set
    var promptTitle = ""
        private set
    private var promptAction: ((String) -> Unit)? = null

    /** True while the drawer is open to pick an app for the home screen (or [pickFolder]). */
    var picking = false
    var pickFolder: String? = null
    var resumed = false
        private set
    private var firstHintPending = false

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    var ticks = 0L
        private set
    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (ticks % 5 == 0L) stats.refresh()
            ui.tick()
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    val palette: Palette get() = Content.palette(save.theme)
    val place: Place get() = Content.places[save.place.coerceIn(0, Content.places.size - 1)]

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (save.installTime == 0L) save.installTime = System.currentTimeMillis()
        icons.configure(save.iconStyle, palette.frame)
        reloadApps()
        stats.refresh()
        if (!save.initialized) firstRun()
        save.save()
    }

    private fun firstRun() {
        save.initialized = true
        firstHintPending = true
        if (save.migrated) return
        // Phone, messages, camera, browser and one more in the dock; the next eight on the home screen.
        val defaults = actions.defaultPackages().mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg && !it.isWork } }
        var d = 0
        for (a in defaults) if (d < SaveData.DOCK) save.dock[d++] = a.key
        var h = 0
        for (a in defaults.drop(d) + apps) {
            if (h >= 8) break
            if (!placed(a.key)) save.home[h++] = a.key
        }
        for (a in apps) {
            if (d >= SaveData.DOCK) break
            if (!placed(a.key)) save.dock[d++] = a.key
        }
    }

    fun resume() {
        resumed = true
        stats.refresh()
        if (actions.isDefaultHome()) achievement("home")
        if (!ticking) {
            ticking = true
            handler.postDelayed(tick, TICK_MS)
        }
        if (firstHintPending) {
            firstHintPending = false
            feed(Tag.NEWS, if (save.migrated) "Your inventory moved in. The rest is in the <col=hi>Backpack</col> folder if it didn't fit. ${Content.FIRST_HINT}" else Content.FIRST_HINT)
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
        ui.hideExamine()
        ui.closeSettings()
        ui.closeFolder()
        cancelInput()
        if (ui.drawerOpen) ui.closeDrawer()
    }

    /** Home pressed while already home: close things, then go back to the home page. */
    fun homePressed() {
        var acted = ui.hideMenu()
        acted = ui.hideChoice() || acted
        acted = ui.hideExamine() || acted
        acted = ui.closeSettings() || acted
        acted = ui.closeFolder() || acted
        if (inputMode != InputMode.NONE) {
            cancelInput()
            acted = true
        }
        if (ui.drawerOpen) {
            ui.closeDrawer()
            acted = true
        }
        if (!acted && ui.page != PAGE_HOME) ui.showPage(PAGE_HOME)
        ui.invalidateAll()
    }

    fun back(): Boolean {
        if (ui.hideChoice() || ui.hideMenu() || ui.hideExamine() || ui.closeSettings()) return true
        if (inputMode != InputMode.NONE) {
            cancelInput()
            return true
        }
        if (ui.closeFolder()) return true
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
        if (::ui.isInitialized) {
            ui.drawerRefresh()
            ui.invalidateAll()
        }
    }

    fun onBatteryChanged() {
        if (::ui.isInitialized) ui.invalidateAll()
    }

    // ------------------------------------------------------------------ lookups

    fun app(key: String?): AppEntry? = key?.let { byKey[it] }
    private fun cells(s: Slot) = if (s.dock) save.dock else save.home
    fun raw(s: Slot): String? = cells(s).getOrNull(s.index)
    fun folder(id: String?): Folder? = id?.let { save.folders[it] }

    fun cell(s: Slot): Cell? {
        val k = raw(s) ?: return null
        if (k.startsWith(SaveData.FOLDER)) {
            val f = folder(k.removePrefix(SaveData.FOLDER)) ?: return null
            return Cell.Dir(f)
        }
        return app(k)?.let { Cell.App(it) }
    }

    fun folderApps(f: Folder): List<AppEntry> = f.items.mapNotNull { app(it) }
    fun slotOfFolder(id: String): Slot? {
        val ref = SaveData.FOLDER + id
        save.home.indexOf(ref).takeIf { it >= 0 }?.let { return Slot(false, it) }
        save.dock.indexOf(ref).takeIf { it >= 0 }?.let { return Slot(true, it) }
        return null
    }

    /** True if the app sits anywhere on the home screen: grid, dock or inside a folder. */
    fun placed(key: String): Boolean = save.home.any { it == key } || save.dock.any { it == key } ||
        save.folders.values.any { key in it.items }

    fun folderOf(key: String): Folder? = save.folders.values.firstOrNull { key in it.items }

    fun level(s: Skill) = Xp.level(save.xp[s.ordinal], s.maxLevel)
    fun xp(s: Skill) = save.xp[s.ordinal]
    fun totalLevel() = Skill.values().sumOf { level(it) }
    fun totalXp() = save.xp.sum()
    fun combatLevel() = Xp.combatLevel { level(it) }
    fun launches(app: AppEntry) = save.launches[app.key] ?: 0
    fun appsTraining(s: Skill) = apps.filter { it.skill == s }
    fun unlockedTracks() = min(Content.tracks.size, 6 + (totalLevel() - 38) / 10)
    fun achievementsDone() = save.achievements.size

    fun recentApps(n: Int): List<AppEntry> = save.lastUsed.entries.sortedByDescending { it.value }
        .mapNotNull { app(it.key) }.distinctBy { it.key }.take(n)

    /** All apps A to Z, or the best matches first while searching. */
    fun drawerItems(query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty() || q.startsWith("::") || q.startsWith("/")) return apps
        return apps.filter { it.label.lowercase().contains(q) }
            .sortedWith(compareBy({ rank(it.label.lowercase(), q) }, { it.label.lowercase() }))
    }

    private fun rank(label: String, q: String) = when {
        label.startsWith(q) -> 0
        label.split(' ', '-', '.').any { it.startsWith(q) } -> 1
        else -> 2
    }

    // ------------------------------------------------------------------ messages

    fun feed(tag: Tag, text: String) {
        val line = FeedLine(tag, text, System.currentTimeMillis())
        feed.add(line)
        while (feed.size > 60) feed.removeAt(0)
        if (::ui.isInitialized) ui.toast(line)
    }

    fun esc(s: String) = s.replace('<', '‹').replace('>', '›')

    // ------------------------------------------------------------------ launching & XP

    fun launch(app: AppEntry, bounds: Rect? = null, options: Bundle? = null) {
        haptic()
        if (!source.launch(app, bounds, options)) {
            feed(Tag.ERR, "Nothing interesting happens. ${esc(app.label)} won't open.")
            return
        }
        save.launches[app.key] = launches(app) + 1
        save.lastUsed[app.key] = System.currentTimeMillis()
        val skill = app.skill
        award(skill, skill.xpPerLaunch)
        if (skill != Skill.CONSTITUTION) award(Skill.CONSTITUTION, max(1, skill.xpPerLaunch / 4), drop = false)
        save.save()
    }

    fun award(skill: Skill, amount: Int, drop: Boolean = true) {
        val i = skill.ordinal
        val before = level(skill)
        save.xp[i] = min(Xp.MAX, save.xp[i] + amount)
        if (drop && save.xpDrops && ::ui.isInitialized) ui.xpDrop(skill, amount)
        val after = level(skill)
        if (after > before && ::ui.isInitialized) {
            val extra = if (after == skill.maxLevel) "Maximum level. Go outside." else "Total level ${totalLevel()}"
            ui.banner(Banner(Banner.Kind.LEVEL, "Level up!", "${skill.title} level $after", extra, skill))
            if (after >= 50) achievement("level50")
            if (totalLevel() >= 100) achievement("total100")
        }
    }

    /** One-off achievements: the first time you do the thing, it pays out. */
    fun achievement(id: String) {
        if (id in save.achievements) return
        val def = Content.achievements.firstOrNull { it.id == id } ?: return
        save.achievements += id
        save.save()
        if (!::ui.isInitialized) return
        ui.banner(Banner(Banner.Kind.ACHIEVEMENT, "Achievement", def.title, def.hint))
        ui.invalidateAll()
    }

    fun haptic() {
        if (save.haptics && ::ui.isInitialized) ui.haptic()
    }

    // ------------------------------------------------------------------ home screen, dock and folders

    fun firstFreeHome(): Int = (0 until SaveData.HOME).firstOrNull { cell(Slot(false, it)) == null } ?: -1
    fun firstFreeDock(): Int = (0 until SaveData.DOCK).firstOrNull { cell(Slot(true, it)) == null } ?: -1

    private fun changed() {
        save.save()
        if (::ui.isInitialized) ui.invalidateAll()
    }

    fun addToHome(app: AppEntry): Boolean {
        if (placed(app.key)) {
            feed(Tag.GAME, "${esc(app.label)} is already on your home screen.")
            return true
        }
        val free = firstFreeHome()
        if (free < 0) {
            feed(Tag.ERR, "Your home screen is full. Try putting things in a folder.")
            return false
        }
        save.home[free] = app.key
        achievement("bank")
        changed()
        return true
    }

    fun addToDock(app: AppEntry): Boolean {
        if (save.dock.any { it == app.key }) return true
        val free = firstFreeDock()
        if (free < 0) {
            feed(Tag.ERR, "The dock is full. Drag something out of it first.")
            return false
        }
        save.dock[free] = app.key
        changed()
        return true
    }

    fun removeFrom(s: Slot) {
        cells(s)[s.index] = null
        changed()
    }

    /** Drag and drop between cells. Whatever is at [to] swaps into [from]. */
    fun move(from: Slot, to: Slot) {
        if (from == to) return
        val a = cells(from)
        val b = cells(to)
        val t = b[to.index]
        b[to.index] = a[from.index]
        a[from.index] = t
        changed()
    }

    /** Puts [key] at [to]. Whatever was there moves to the next free cell. */
    fun place(key: String, to: Slot) {
        val arr = cells(to)
        val existing = arr.indexOf(key)
        if (existing >= 0) arr[existing] = null
        val occupant = arr[to.index]?.takeIf { it != key && cell(to) != null }
        arr[to.index] = key
        if (occupant != null) {
            val free = if (to.dock) firstFreeDock() else firstFreeHome()
            if (free >= 0) arr[free] = occupant else feed(Tag.ERR, "No room left, so something got bumped off the home screen.")
        }
        if (!to.dock) achievement("bank")
        changed()
    }

    private fun autoName(a: AppEntry, b: AppEntry?): String {
        val s = if (b == null || a.skill == b.skill) a.skill else null
        s?.let { return Content.folderNames[it] ?: it.title }
        val both = "${a.label} & ${b?.label}"
        return if (both.length <= 20) both else "Folder"
    }

    /**
     * [key] was dropped onto [target]. Onto an app: the two become a folder. Onto a folder: it
     * joins. [from] is where the dragged app came from, so its old cell can be cleared.
     */
    fun mergeInto(target: Slot, key: String, from: Slot?, fromFolder: String?) {
        val incoming = app(key) ?: return
        when (val c = cell(target)) {
            is Cell.Dir -> {
                if (c.folder.id == fromFolder) return
                fromFolder?.let { detach(it, key) }
                from?.let { cells(it)[it.index] = null }
                if (key !in c.folder.items) c.folder.items += key
                feed(Tag.GAME, "${esc(incoming.label)} goes into <col=hi>${esc(c.folder.name)}</col>.")
            }
            is Cell.App -> {
                if (c.app.key == key) return
                fromFolder?.let { detach(it, key) }
                from?.let { cells(it)[it.index] = null }
                val f = Folder("f" + SystemClock.elapsedRealtime().toString(36) + Random.nextInt(1000), autoName(c.app, incoming), mutableListOf(c.app.key, key))
                save.folders[f.id] = f
                cells(target)[target.index] = SaveData.FOLDER + f.id
                achievement("folder")
                feed(Tag.GAME, "New folder: <col=hi>${esc(f.name)}</col>. Hold it to rename.")
            }
            null -> {
                fromFolder?.let { detach(it, key) }
                if (from != null) move(from, target) else place(key, target)
                return
            }
        }
        changed()
    }

    /** Takes [key] out of folder [id] without putting it anywhere; dissolves a folder left with one app. */
    private fun detach(id: String, key: String) {
        val f = folder(id) ?: return
        f.items.remove(key)
        val slot = slotOfFolder(id) ?: return
        val left = f.items.filter { app(it) != null }
        when {
            f.items.isEmpty() -> cells(slot)[slot.index] = null
            left.size == 1 && f.items.size == 1 -> {
                cells(slot)[slot.index] = left[0]
                save.folders.remove(id)
                if (::ui.isInitialized) ui.closeFolder()
            }
        }
    }

    fun removeFromFolder(id: String, key: String) {
        detach(id, key)
        changed()
    }

    /** "Take out": out of the folder and onto the first free home cell. */
    fun takeOut(id: String, key: String) {
        val free = firstFreeHome()
        if (free < 0) {
            feed(Tag.ERR, "No free space on the home screen to take it out to.")
            return
        }
        detach(id, key)
        if (!placed(key)) save.home[firstFreeHome().takeIf { it >= 0 } ?: free] = key
        changed()
    }

    /** An app from a folder dropped on a plain cell. */
    fun moveOutOfFolder(id: String, key: String, to: Slot) {
        detach(id, key)
        place(key, to)
    }

    fun addAppToFolder(id: String, app: AppEntry) {
        val f = folder(id) ?: return
        if (app.key in f.items) return
        f.items += app.key
        changed()
    }

    fun renameFolder(id: String) {
        val f = folder(id) ?: return
        prompt("Name this folder", f.name) { n ->
            if (n.isNotBlank()) {
                f.name = n.trim().take(20)
                changed()
            }
        }
    }

    /** Puts a folder's apps back on the home screen, as far as space allows. */
    fun ungroup(id: String) {
        val f = folder(id) ?: return
        val slot = slotOfFolder(id) ?: return
        val items = f.items.filter { app(it) != null }.toMutableList()
        cells(slot)[slot.index] = items.removeFirstOrNull()
        val left = ArrayList<String>()
        for (k in items) {
            val free = firstFreeHome()
            if (free >= 0) save.home[free] = k else left += k
        }
        if (left.isNotEmpty()) {
            f.items.clear()
            f.items += left
            val free = firstFreeHome()
            if (free >= 0) save.home[free] = SaveData.FOLDER + id
            feed(Tag.ERR, "Ran out of room: ${left.size} app${if (left.size == 1) "" else "s"} stayed in ${esc(f.name)}.")
        } else {
            save.folders.remove(id)
        }
        ui.closeFolder()
        changed()
    }

    fun picked(app: AppEntry) {
        val fid = pickFolder
        picking = false
        pickFolder = null
        ui.closeDrawer()
        if (fid != null) {
            addAppToFolder(fid, app)
            ui.openFolder(fid)
        } else {
            addToHome(app)
        }
    }

    fun pickForFolder(id: String) {
        ui.closeFolder()
        picking = true
        pickFolder = id
        ui.openDrawer(search = false, pick = true)
    }

    private fun subtitle(app: AppEntry): String {
        val n = launches(app)
        return "Trains ${app.skill.title} · $n use${if (n == 1) "" else "s"}"
    }

    /** "Put in a folder": every existing folder, or a new one. */
    private fun chooseFolder(app: AppEntry, slot: Slot?) {
        val opts = save.folders.values.filter { slotOfFolder(it.id) != null && app.key !in it.items }.map { f ->
            ChoiceOption(f.name) {
                if (slot != null) cells(slot)[slot.index] = null
                f.items += app.key
                feed(Tag.GAME, "${esc(app.label)} goes into <col=hi>${esc(f.name)}</col>.")
                changed()
            }
        } + ChoiceOption("New folder...", primary = true) {
            val target = slot?.takeIf { !it.dock } ?: firstFreeHome().takeIf { it >= 0 }?.let { Slot(false, it) }
            if (target == null) {
                feed(Tag.ERR, "Your home screen is full, so there's nowhere to put a new folder.")
                return@ChoiceOption
            }
            if (slot != null && slot != target) cells(slot)[slot.index] = null
            val f = Folder("f" + SystemClock.elapsedRealtime().toString(36), autoName(app, null), mutableListOf(app.key))
            save.folders[f.id] = f
            cells(target)[target.index] = SaveData.FOLDER + f.id
            achievement("folder")
            changed()
            renameFolder(f.id)
        }
        ui.showChoice(Choice("Put ${app.label} in a folder", "Which folder?", opts))
    }

    /** Long-press menu for an app: held on the home screen [slot], in folder [folderId], or in the drawer. */
    fun appMenu(app: AppEntry, slot: Slot?, folderId: String?, x: Float, y: Float) {
        haptic()
        val opts = ArrayList<MenuOption>()
        when {
            folderId != null -> {
                opts += MenuOption("Take out of folder") { takeOut(folderId, app.key) }
                opts += MenuOption("Remove from folder") { removeFromFolder(folderId, app.key) }
            }
            slot == null -> {
                if (!placed(app.key)) opts += MenuOption("Add to home screen") { addToHome(app) }
                if (save.dock.none { it == app.key }) opts += MenuOption("Add to dock") { addToDock(app) }
                if (!placed(app.key)) opts += MenuOption("Put in a folder") { chooseFolder(app, null) }
            }
            slot.dock -> {
                opts += MenuOption("Remove from dock") { removeFrom(slot) }
                opts += MenuOption("Put in a folder") { chooseFolder(app, slot) }
            }
            else -> {
                opts += MenuOption("Remove from home") { removeFrom(slot) }
                if (save.dock.none { it == app.key }) opts += MenuOption("Move to dock") { if (addToDock(app)) removeFrom(slot) }
                opts += MenuOption("Put in a folder") { chooseFolder(app, slot) }
            }
        }
        opts += MenuOption("Examine") { examine(app) }
        opts += MenuOption("App info") { source.details(app) }
        opts += MenuOption("Uninstall", danger = true) { uninstall(app) }
        ui.showMenu(ItemMenu(app.label, subtitle(app), palette.hi, opts, x, y))
    }

    fun folderMenu(slot: Slot, x: Float, y: Float) {
        val f = (cell(slot) as? Cell.Dir)?.folder ?: return
        haptic()
        val n = folderApps(f).size
        ui.showMenu(ItemMenu(f.name, "Folder · $n app${if (n == 1) "" else "s"}", palette.hi, listOf(
            MenuOption("Open") { ui.openFolder(f.id) },
            MenuOption("Rename") { renameFolder(f.id) },
            MenuOption("Add apps") { pickForFolder(f.id) },
            MenuOption("Ungroup") { ungroup(f.id) },
            MenuOption("Remove from home", danger = true) { removeFrom(slot) },
        ), x, y))
    }

    /** Long-press on empty space. */
    fun homeMenu(x: Float, y: Float) {
        haptic()
        ui.showMenu(ItemMenu("Home screen", "World ${save.world} · ${place.name}", palette.hi, listOf(
            MenuOption("Add apps") {
                picking = true
                pickFolder = null
                ui.openDrawer(search = false, pick = true)
            },
            MenuOption("Launcher settings") { ui.openSettings() },
            MenuOption(if (save.scene == 0) "Use my wallpaper" else "Show Gielinor") {
                save.scene = 1 - save.scene
                changed()
            },
            MenuOption("World hop") { worldHop() },
            MenuOption("Android settings") { actions.settings(Settings.ACTION_SETTINGS) },
        ), x, y))
    }

    fun examine(app: AppEntry) {
        ui.hideMenu()
        ui.showExamine(app)
        achievement("examine")
    }

    fun examineLine(app: AppEntry): String {
        val line = Content.examines[Math.floorMod(app.key.hashCode(), Content.examines.size)]
        val vowel = app.label.firstOrNull()?.lowercaseChar() in "aeiou".toSet()
        return line.replace("A %s", (if (vowel) "An " else "A ") + app.label)
            .replace("a %s", (if (vowel) "an " else "a ") + app.label)
            .replace("%s", app.label)
    }

    fun uninstall(app: AppEntry) {
        ui.showChoice(Choice(
            "Destroy item", "Really uninstall ${app.label}? You can't reclaim it.",
            listOf(
                ChoiceOption("Yes, uninstall it.", primary = true, danger = true) { source.uninstall(app) },
                ChoiceOption("No, keep it."),
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
                text.startsWith("::") || text.startsWith("/") -> {
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
        val parts = raw.trimStart(':', '/').trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase(Locale.US)
        val arg = parts.getOrElse(1) { "" }.trim()
        when (cmd) {
            "help", "?", "", "commands" -> feed(Tag.GAME, Content.HELP)
            "bank", "apps" -> ui.openDrawer(search = false, pick = false)
            "stats", "xp", "levels" -> feed(Tag.XP,
                "Total level ${totalLevel()}, combat ${combatLevel()}, ${Rs3Format.number(totalXp())} XP, ${save.questPoints} quest points.")
            "name" -> if (arg.isNotEmpty()) setName(arg) else askName()
            "theme" -> {
                val p = Content.palettes.firstOrNull { it.id.equals(arg, true) || it.name.equals(arg, true) }
                if (p != null) setTheme(p.id) else feed(Tag.GAME, "Themes: " + Content.palettes.joinToString(", ") { it.name } + ".")
            }
            "hop", "world" -> worldHop()
            "wiki" -> actions.url(if (arg.isEmpty()) "https://runescape.wiki/" else "https://runescape.wiki/?search=" + Uri.encode(arg))
            "ge", "price" -> actions.url("https://runescape.wiki/w/Special:Search?search=" + Uri.encode("$arg exchange"))
            "settings", "options" -> ui.openSettings()
            "log" -> ui.showPage(PAGE_LOG)
            "reset" -> confirmReset()
            "home" -> actions.homeSettings()
            "wallpaper" -> actions.wallpaper()
            "dupe", "gf", "bond" -> feed(Tag.GAME, "Nothing interesting happens.")
            "logout", "quit" -> quit()
            else -> {
                val hit = drawerItems(raw.trimStart(':', '/')).firstOrNull()
                if (hit != null) launch(hit) else feed(Tag.ERR, "Unknown command ::$cmd. Try ::help.")
            }
        }
    }

    fun askName() = prompt("Enter your display name", save.name) { setName(it) }

    fun setName(n: String) {
        val name = n.trim().take(12)
        if (name.isEmpty()) return
        save.name = name
        changed()
        feed(Tag.GAME, "Your display name is now <col=hi>${esc(name)}</col>.")
    }

    fun setTheme(id: String) {
        if (save.theme == id) return
        save.theme = id
        icons.configure(save.iconStyle, palette.frame)
        achievement("theme")
        changed()
        ui.themeChanged()
    }

    fun setIconStyle(style: Int) {
        save.iconStyle = style
        icons.configure(style, palette.frame)
        changed()
    }

    fun worldHop() {
        save.world = 1 + Random.nextInt(140)
        save.place = Random.nextInt(Content.places.size)
        changed()
        ui.worldHop()
        feed(Tag.GAME, "You hop to world ${save.world}. You arrive in <col=hi>${place.name}</col>.")
    }

    fun confirmReset() {
        ui.showChoice(Choice(
            "Wise Old Man", "Reset all of your XP? Your home screen and folders stay as they are.",
            listOf(
                ChoiceOption("Yes. Start over.", primary = true, danger = true) {
                    save.xp.fill(0L)
                    save.xp[Skill.CONSTITUTION.ordinal] = Xp.forLevel(10)
                    save.launches.clear()
                    changed()
                    feed(Tag.GAME, "Your stats have been reset. Back to combat level 3.")
                },
                ChoiceOption("No, I worked hard for that."),
            ),
        ))
    }

    fun quit() {
        ui.showChoice(Choice(
            "Logout", "Switch to a different home app?",
            listOf(
                ChoiceOption("Yes, pick another home app.", primary = true) { actions.homeSettings() },
                ChoiceOption("No, keep playing."),
            ),
        ))
    }

    // ------------------------------------------------------------------ quests

    fun newQuest() = prompt("Name your quest") { t ->
        if (t.isNotBlank()) {
            save.quests += Quest("q" + SystemClock.elapsedRealtime().toString(36), t.trim().take(48), false, System.currentTimeMillis())
            changed()
        }
    }

    fun questTap(q: Quest) {
        haptic()
        q.done = !q.done
        if (q.done) {
            save.questPoints++
            ui.banner(Banner(Banner.Kind.QUEST, "Quest complete!", q.title, "Quest points: ${save.questPoints}"))
            achievement("quest")
        } else {
            save.questPoints = max(0, save.questPoints - 1)
        }
        changed()
    }

    fun questMenu(q: Quest, x: Float, y: Float) {
        haptic()
        ui.showMenu(ItemMenu(q.title, if (q.done) "Completed" else "In progress", palette.hi, listOf(
            MenuOption(if (q.done) "Mark not done" else "Mark complete") { questTap(q) },
            MenuOption("Rename") {
                prompt("Rename quest", q.title) { n ->
                    if (n.isNotBlank()) {
                        q.title = n.trim().take(48)
                        changed()
                    }
                }
            },
            MenuOption("Abandon", danger = true) {
                save.quests.remove(q)
                changed()
            },
        ), x, y))
    }

    fun clearDoneQuests() {
        save.quests.removeAll { it.done }
        changed()
    }

    // ------------------------------------------------------------------ quick settings & music

    fun prayer(p: PrayerDef) {
        haptic()
        val ok = when (p.id) {
            "internet" -> actions.internet()
            "torch" -> {
                if (!actions.hasTorch()) {
                    feed(Tag.ERR, "You have no light source. (No flashlight on this phone.)")
                    return
                }
                actions.toggleTorch()
            }
            "bluetooth" -> actions.bluetooth()
            "dnd" -> actions.doNotDisturb()
            "volume" -> {
                actions.volume()
                true
            }
            "location" -> actions.location()
            "saver" -> actions.batterySaver()
            "hotspot" -> actions.hotspot()
            "airplane" -> actions.airplane()
            else -> actions.settings(Settings.ACTION_SETTINGS)
        }
        if (!ok) {
            feed(Tag.ERR, "You have run out of prayer points. (Nothing could open that setting.)")
            return
        }
        award(Skill.PRAYER, 8)
        achievement("prayer")
        changed()
    }

    fun playTrack(i: Int) {
        haptic()
        if (i >= unlockedTracks()) {
            feed(Tag.GAME, "You have not unlocked this piece of music yet!")
            return
        }
        save.track = i
        actions.media(KeyEvent.KEYCODE_MEDIA_PLAY)
        changed()
    }

    fun mediaKey(code: Int) {
        haptic()
        actions.media(code)
        val n = max(1, unlockedTracks())
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> save.track = (save.track + 1) % n
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> save.track = (save.track - 1 + n) % n
        }
        changed()
    }

    fun deviceReport() {
        feed(Tag.GAME, "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}. " + String.format(Locale.US,
            "%.1f of %.1f GB memory free, %.0f of %.0f GB storage free.", stats.ramFreeGb, stats.ramTotalGb, stats.storageFreeGb, stats.storageTotalGb))
    }

    /** Fills in a lived-in save for screenshots. Only reachable from debug builds and tests. */
    fun demoSeed() {
        val r = Random(2007)
        apps.forEachIndexed { i, a ->
            val n = if (i % 4 == 0) 20 + r.nextInt(120) else r.nextInt(12)
            if (n > 0) {
                save.launches[a.key] = n
                save.lastUsed[a.key] = System.currentTimeMillis() - r.nextInt(3 * 86_400) * 1000L
            }
        }
        for (s in Skill.values()) save.xp[s.ordinal] = Xp.forLevel(8 + r.nextInt(60)) + r.nextInt(900)
        save.xp[Skill.RUNECRAFTING.ordinal] = Xp.forLevel(3)
        save.questPoints = 7
        save.achievements += listOf("home", "bank", "examine", "log")
        if (save.quests.isEmpty()) {
            save.quests += Quest("q1", "Buy groceries", false, System.currentTimeMillis())
            save.quests += Quest("q2", "Call mum back", false, System.currentTimeMillis())
            save.quests += Quest("q3", "Pay the rent", true, System.currentTimeMillis())
        }
        // A social folder in the last row, if there are enough social-ish apps around.
        val group = apps.filter { it.skill == Skill.THIEVING || it.skill == Skill.RANGED }.filter { !placed(it.key) }.take(4)
        if (group.size >= 2 && save.folders.isEmpty()) {
            val free = firstFreeHome()
            if (free >= 0) {
                val f = Folder("social", "Social", group.map { it.key }.toMutableList())
                save.folders[f.id] = f
                save.home[free] = SaveData.FOLDER + f.id
            }
        }
        var slot = 0
        for (a in apps) {
            if (save.home.count { it != null } >= 14) break
            while (slot < SaveData.HOME && save.home[slot] != null) slot++
            if (slot >= SaveData.HOME) break
            if (!placed(a.key)) save.home[slot] = a.key
        }
        changed()
    }

    companion object {
        const val TICK_MS = 1000L
        const val PAGE_LOG = 0
        const val PAGE_HOME = 1
    }
}

object Rs3Format {
    fun number(n: Long): String = String.format(Locale.US, "%,d", n)

    /** 1,234 -> "1.2K", 12,345,678 -> "12.3M", like the XP counter. */
    fun short(n: Long): String = when {
        n >= 10_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
        n >= 100_000 -> "${n / 1000}K"
        n >= 10_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
        else -> number(n)
    }
}
