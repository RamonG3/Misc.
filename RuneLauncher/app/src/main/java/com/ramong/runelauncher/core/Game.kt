package com.ramong.runelauncher.core

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The launcher's brain: apps, inventory, XP, chat, quests and every action the UI can trigger.
 * The views only draw [Game] state and forward taps here.
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
        val bankOpen: Boolean
        val currentTab: Int
        fun invalidateAll()
        fun tick()
        fun openBank(search: Boolean, skill: Skill?)
        fun closeBank()
        fun bankRefresh()
        fun showMenu(options: List<MenuOption>, x: Float, y: Float)
        fun hideMenu(): Boolean
        fun openKeyboard(initial: String)
        fun closeKeyboard()
        fun xpDrop(skill: Skill, amount: Int)
        fun fireworks()
        fun overhead(text: String)
        fun emote(name: String): Boolean
        fun selectTab(index: Int)
        fun worldHop()
        fun haptic()
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
    val chat = ArrayList<ChatLine>()
    val dialogues = ArrayDeque<Dialogue>()

    /** Inventory slot picked with "Use", waiting for a second item. -1 when not in use mode. */
    var useSlot = -1
    var inputMode = InputMode.NONE
        private set
    var inputText = ""
        private set
    var promptTitle = ""
        private set
    private var promptAction: ((String) -> Unit)? = null

    var nowPlaying = 0
    var world = 301 + Random.nextInt(250)
    var resumed = false
        private set
    private var pendingFireworks = false

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    var ticks = 0L
        private set
    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (ticks % 8 == 0L) stats.refresh()
            ui.tick()
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (save.installTime == 0L) save.installTime = System.currentTimeMillis()
        icons.pixelSize = pixelSizeFor(save.iconStyle)
        reloadApps()
        stats.refresh()
        message("Welcome to RuneScape.")
        if (!save.initialized) firstRun()
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
        for (def in Content.builtinQuests) save.quests += Quest(def.id, def.name, Quest.NOT_STARTED, true)
        message("Tip: tap the chat line to search your bank. Type <col=0000ff>::help</col> for commands.")
        message("Swipe up, or use the bank chest, to see all of your items.")
    }

    fun resume() {
        resumed = true
        stats.refresh()
        if (actions.isDefaultHome()) questEvent("tutorial")
        if (pendingFireworks) {
            pendingFireworks = false
            ui.fireworks()
        }
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
        cancelInput()
        if (ui.bankOpen) ui.closeBank()
        useSlot = -1
    }

    /** Home pressed while already home: close things, then "Home Teleport". */
    fun homePressed() {
        var acted = ui.hideMenu()
        if (inputMode != InputMode.NONE) {
            cancelInput()
            acted = true
        }
        if (ui.bankOpen) {
            ui.closeBank()
            acted = true
        }
        if (useSlot >= 0) {
            useSlot = -1
            acted = true
        }
        if (!acted && ui.currentTab != INVENTORY_TAB) {
            ui.selectTab(INVENTORY_TAB)
            acted = true
        }
        if (!acted) ui.emote("CAST")
        ui.invalidateAll()
    }

    fun back(): Boolean {
        if (ui.hideMenu()) return true
        if (inputMode != InputMode.NONE) {
            cancelInput()
            return true
        }
        if (ui.bankOpen) {
            ui.closeBank()
            return true
        }
        if (useSlot >= 0) {
            useSlot = -1
            ui.invalidateAll()
            return true
        }
        return false
    }

    fun reloadApps() {
        apps = source.load()
        byKey.clear()
        apps.forEach { byKey[it.key] = it }
        if (::ui.isInitialized) {
            ui.bankRefresh()
            ui.invalidateAll()
        }
    }

    // ------------------------------------------------------------------ lookups

    fun app(key: String?): AppEntry? = key?.let { byKey[it] }
    fun invApp(slot: Int): AppEntry? = app(save.inventory[slot])
    fun equipApp(slot: EquipSlot): AppEntry? = app(save.equipment[slot.ordinal])
    fun level(s: Skill) = Xp.level(save.xp[s.ordinal])
    fun xp(s: Skill) = save.xp[s.ordinal]
    fun totalLevel() = Skill.values().sumOf { level(it) }
    fun totalXp() = save.xp.sum()
    fun combatLevel() = Xp.combatLevel { level(it) }
    fun questPoints() = save.quests.count { it.state == Quest.DONE }
    fun unlockedTracks() = min(Content.tracks.size, 6 + totalLevel() / 8)
    fun appsTraining(s: Skill) = apps.count { it.skill == s }

    fun hoverText(): String {
        val use = if (useSlot >= 0) invApp(useSlot) else null
        return if (use != null) "Use <col=ff9040>${use.label}</col> ->" else "Walk here"
    }

    fun bankItems(query: String, skill: Skill?): List<AppEntry> {
        val q = query.trim().lowercase()
        var seq = apps.asSequence()
        if (skill != null) seq = seq.filter { it.skill == skill }
        val searching = q.isNotEmpty() && !q.startsWith("::") && !q.startsWith("/")
        if (searching) seq = seq.filter { it.label.lowercase().contains(q) }
        val list = seq.toList()
        return when {
            searching -> list.sortedWith(compareBy({ rank(it.label.lowercase(), q) }, { it.label.lowercase() }))
            save.bankSort == 1 -> list.sortedWith(compareByDescending<AppEntry> { save.launches[it.key] ?: 0 }.thenBy { it.label.lowercase() })
            save.bankSort == 2 -> list.sortedWith(compareBy({ it.skill.ordinal }, { it.label.lowercase() }))
            else -> list
        }
    }

    private fun rank(label: String, q: String) = when {
        label.startsWith(q) -> 0
        label.split(' ', '-', '.').any { it.startsWith(q) } -> 1
        else -> 2
    }

    // ------------------------------------------------------------------ chat

    fun message(text: String) = addChat(ChatLine(Channel.GAME, text))

    private fun addChat(line: ChatLine) {
        chat.add(line)
        while (chat.size > 150) chat.removeAt(0)
        if (::ui.isInitialized) ui.invalidateAll()
    }

    private fun esc(s: String) = s.replace('<', '‹').replace('>', '›')

    fun say(raw: String) {
        val text = raw.trim().take(80)
        if (text.isEmpty()) return
        val body = ChatEffects.parse(text).body
        addChat(ChatLine(Channel.PUBLIC, "${esc(save.name)}: <col=0000ff>${esc(body)}</col>"))
        ui.overhead(text)
        questEvent("chat")
        val l = body.lowercase()
        when {
            "buying gf" in l -> message("Nothing interesting happens.")
            "doubling money" in l || "double money" in l ->
                message("<col=ef1020>Nobody will ever ask for your password. Especially not to double your money.</col>")
            "hunter2" in l -> message("All I see is *******.")
            "free armour trimming" in l -> message("Nobody takes you up on your offer.")
        }
    }

    // ------------------------------------------------------------------ launching & xp

    fun launch(app: AppEntry, bounds: Rect? = null, options: Bundle? = null) {
        haptic()
        if (!source.launch(app, bounds, options)) {
            message("Nothing interesting happens.")
            return
        }
        save.launches[app.key] = (save.launches[app.key] ?: 0) + 1
        save.lastUsed[app.key] = System.currentTimeMillis()
        val skill = app.skill
        award(skill, skill.xpPerAction)
        if (skill != Skill.HITPOINTS) award(Skill.HITPOINTS, max(1, skill.xpPerAction / 3))
        if (!save.hasPet && Random.nextInt(PET_CHANCE) == 0) {
            save.hasPet = true
            message("<col=ef1020>You have a funny feeling like you're being followed.</col>")
        }
        save.save()
    }

    fun award(skill: Skill, amount: Int) {
        val i = skill.ordinal
        val before = level(skill)
        save.xp[i] = min(Xp.MAX, save.xp[i] + amount)
        if (save.xpDrops) ui.xpDrop(skill, amount)
        val after = level(skill)
        if (after > before) levelUp(skill, after)
    }

    private fun levelUp(skill: Skill, level: Int) {
        dialogues.addLast(Dialogue.LevelUp(skill, level))
        message(
            if (level == 99) "Congratulations, you've reached the highest possible ${skill.title} level of 99."
            else "Congratulations, you've just advanced your ${skill.title} level. You are now level $level."
        )
        if (resumed) ui.fireworks() else pendingFireworks = true
        if (totalLevel() >= 100) questEvent("grind")
        ui.invalidateAll()
    }

    fun haptic() {
        if (save.haptics) ui.haptic()
    }

    // ------------------------------------------------------------------ inventory, bank, equipment

    fun firstFreeInv(): Int = (0 until 28).firstOrNull { invApp(it) == null } ?: -1

    fun inventoryTap(slot: Int, bounds: Rect?) {
        val app = invApp(slot)
        if (useSlot >= 0) {
            val from = useSlot
            useSlot = -1
            if (app != null && slot != from) message("Nothing interesting happens.")
            ui.invalidateAll()
            return
        }
        if (app != null) launch(app, bounds)
    }

    fun inventoryMenu(slot: Int, x: Float, y: Float) {
        val app = invApp(slot) ?: return
        haptic()
        ui.showMenu(
            listOf(
                MenuOption("Open", app.label) { launch(app) },
                MenuOption("Use", app.label) {
                    useSlot = slot
                    ui.invalidateAll()
                },
                MenuOption("Wield", app.label) { wield(slot) },
                MenuOption("Check", app.label) { source.details(app) },
                MenuOption("Drop", app.label) {
                    save.inventory[slot] = null
                    save.save()
                    ui.invalidateAll()
                },
                MenuOption("Examine", app.label) { examine(app) },
                MenuOption("Destroy", app.label) { destroy(app) },
            ),
            x, y,
        )
    }

    fun swapInventory(a: Int, b: Int) {
        if (a == b || a !in 0 until 28 || b !in 0 until 28) return
        val t = save.inventory[a]
        save.inventory[a] = save.inventory[b]
        save.inventory[b] = t
        save.save()
        ui.invalidateAll()
    }

    fun withdraw(app: AppEntry) {
        if (save.inventory.any { it == app.key }) {
            message("You already have a ${app.label} in your inventory.")
            return
        }
        val free = firstFreeInv()
        if (free < 0) {
            message("You don't have enough inventory space.")
            return
        }
        save.inventory[free] = app.key
        save.save()
        questEvent("withdraw")
        ui.invalidateAll()
    }

    fun bankMenu(app: AppEntry, x: Float, y: Float) {
        haptic()
        ui.showMenu(
            listOf(
                MenuOption("Open", app.label) { launch(app) },
                MenuOption("Withdraw-1", app.label) { withdraw(app) },
                MenuOption("Check", app.label) { source.details(app) },
                MenuOption("Examine", app.label) { examine(app) },
                MenuOption("Destroy", app.label) { destroy(app) },
            ),
            x, y,
        )
    }

    fun wield(slot: Int) {
        val key = save.inventory[slot] ?: return
        val order = listOf(
            EquipSlot.WEAPON, EquipSlot.SHIELD, EquipSlot.HEAD, EquipSlot.BODY, EquipSlot.LEGS, EquipSlot.CAPE,
            EquipSlot.NECK, EquipSlot.AMMO, EquipSlot.HANDS, EquipSlot.FEET, EquipSlot.RING,
        )
        val target = order.firstOrNull { equipApp(it) == null } ?: EquipSlot.WEAPON
        val old = save.equipment[target.ordinal]?.takeIf { app(it) != null }
        save.equipment[target.ordinal] = key
        save.inventory[slot] = old
        save.save()
        questEvent("wield")
        ui.invalidateAll()
    }

    fun unequip(slot: EquipSlot) {
        val key = save.equipment[slot.ordinal] ?: return
        val free = firstFreeInv()
        if (free < 0) {
            message("You don't have enough free inventory space to do that.")
            return
        }
        save.inventory[free] = key
        save.equipment[slot.ordinal] = null
        save.save()
        ui.invalidateAll()
    }

    fun equipmentMenu(slot: EquipSlot, x: Float, y: Float) {
        val app = equipApp(slot) ?: return
        haptic()
        ui.showMenu(
            listOf(
                MenuOption("Open", app.label) { launch(app) },
                MenuOption("Remove", app.label) { unequip(slot) },
                MenuOption("Check", app.label) { source.details(app) },
                MenuOption("Examine", app.label) { examine(app) },
            ),
            x, y,
        )
    }

    fun examine(app: AppEntry) {
        val line = Content.examines[Math.floorMod(app.key.hashCode(), Content.examines.size)]
        message(line.replace("%s", app.label))
        val n = save.launches[app.key] ?: 0
        message("It trains ${app.skill.title}. Used $n time${if (n == 1) "" else "s"}.")
        questEvent("examine")
    }

    fun destroy(app: AppEntry) {
        dialogues.addFirst(
            Dialogue.Options(
                "Destroy the ${app.label}? You can't reclaim it.",
                listOf("Yes. (Uninstall it.)" to { source.uninstall(app) }, "No." to {}),
            )
        )
        ui.invalidateAll()
    }

    fun equipmentStats() {
        message("Device: ${Build.MANUFACTURER} ${Build.MODEL}. Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).")
        message(
            String.format(
                java.util.Locale.US, "Prayer bonus: +%.0f GB free of %.0f GB. Memory: %.1f GB, %d%% free.",
                stats.storageFreeGb, stats.storageTotalGb, stats.ramTotalGb, stats.ramFreePct,
            )
        )
    }

    fun itemsKeptOnDeath() {
        val owned = (save.inventory.toList() + save.equipment.toList()).mapNotNull { app(it) }.distinctBy { it.key }
        val top = owned.sortedByDescending { save.launches[it.key] ?: 0 }.take(3)
        if (top.isEmpty()) message("You have nothing worth keeping. Impressive.")
        else message("If you die, you will keep: ${top.joinToString(", ") { it.label }}.")
    }

    // ------------------------------------------------------------------ dialogue & input

    fun continueDialogue() {
        dialogues.removeFirstOrNull()
        ui.invalidateAll()
    }

    fun chooseOption(i: Int) {
        val d = dialogues.firstOrNull() as? Dialogue.Options ?: return
        dialogues.removeFirst()
        ui.invalidateAll()
        d.options.getOrNull(i)?.second?.invoke()
    }

    fun beginSearch() {
        inputMode = InputMode.SEARCH
        inputText = ""
        ui.openBank(true, null)
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
        inputText = t.take(80)
        if (inputMode == InputMode.SEARCH) ui.bankRefresh()
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
                text.startsWith("::") -> {
                    ui.closeBank()
                    command(text)
                }
                text.startsWith("/") -> {
                    ui.closeBank()
                    say(text.drop(1))
                }
                text.isEmpty() -> Unit
                else -> {
                    val hit = bankItems(text, null).firstOrNull()
                    if (hit != null) {
                        launch(hit)
                    } else {
                        ui.closeBank()
                        say(text)
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
        if (wasSearch) ui.bankRefresh()
        ui.invalidateAll()
    }

    // ------------------------------------------------------------------ commands

    fun command(raw: String) {
        val parts = raw.removePrefix("::").trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrElse(1) { "" }.trim()
        when (cmd) {
            "help", "commands", "" -> message(Content.HELP)
            "bank" -> ui.openBank(false, null)
            "wiki" -> actions.url(
                if (arg.isEmpty()) "https://oldschool.runescape.wiki/"
                else "https://oldschool.runescape.wiki/?search=" + android.net.Uri.encode(arg)
            )
            "search", "google" -> if (arg.isNotEmpty()) actions.webSearch(arg) else message("Search for what, exactly?")
            "name" -> if (arg.isNotEmpty()) setName(arg) else askName()
            "emote" -> if (!ui.emote(arg)) message("You don't know that emote.")
            "xp", "stats" -> message(
                "Total level: ${totalLevel()}. Total XP: ${String.format(java.util.Locale.US, "%,d", totalXp())}. " +
                    "Combat level: ${combatLevel()}. Quest points: ${questPoints()}."
            )
            "reset" -> confirmReset()
            "home" -> actions.homeSettings()
            "wallpaper" -> actions.wallpaper()
            "settings" -> actions.settings(Settings.ACTION_SETTINGS)
            "world", "hop" -> worldHop()
            "pet" -> message(
                if (save.hasPet) "Your pet looks up at you expectantly."
                else "You have a funny feeling like you would have been followed..."
            )
            "gf", "bond", "dupe" -> message("Nothing interesting happens.")
            else -> if (!ui.emote(cmd)) message("Unknown command: ::$cmd. Type ::help for a list.")
        }
    }

    fun askName() = prompt("Enter your new display name:", save.name) { setName(it) }

    fun setName(n: String) {
        val name = n.trim().take(12)
        if (name.isEmpty()) return
        save.name = name
        save.save()
        message("Your display name is now <col=0000ff>$name</col>.")
    }

    fun confirmReset() {
        dialogues.addFirst(
            Dialogue.Options(
                "Reset ALL of your experience?",
                listOf(
                    "Yes. Back to Tutorial Island." to {
                        save.xp.fill(0L)
                        save.xp[Skill.HITPOINTS.ordinal] = Xp.forLevel(10)
                        save.save()
                        message("Your stats have been reset. Welcome back to level 3.")
                    },
                    "No." to {},
                ),
            )
        )
        ui.invalidateAll()
    }

    fun worldHop() {
        world = 301 + Random.nextInt(250)
        ui.worldHop()
        message("You hop to world $world.")
    }

    // ------------------------------------------------------------------ quests

    fun questEvent(id: String) {
        val q = save.quests.firstOrNull { it.id == id && it.builtin } ?: return
        if (q.state != Quest.DONE) completeQuest(q)
    }

    private fun completeQuest(q: Quest) {
        q.state = Quest.DONE
        save.save()
        message("Congratulations, you've completed a quest: <col=ef1020>${q.name}</col>")
        dialogues.addLast(Dialogue.Message("Congratulations!", listOf("You have completed ${q.name}!", "Quest Points: ${questPoints()}")))
        if (::ui.isInitialized) ui.invalidateAll()
    }

    fun questTap(q: Quest) {
        if (q.builtin) {
            val hint = Content.builtinQuests.firstOrNull { it.id == q.id }?.hint ?: ""
            message("<col=ef1020>${q.name}</col>: $hint")
            if (q.state == Quest.NOT_STARTED) {
                q.state = Quest.STARTED
                save.save()
            }
        } else {
            q.state = (q.state + 1) % 3
            if (q.state == Quest.DONE) completeQuest(q) else save.save()
        }
        ui.invalidateAll()
    }

    fun questMenu(q: Quest, x: Float, y: Float) {
        val opts = ArrayList<MenuOption>()
        if (!q.builtin) {
            opts += MenuOption("Rename", q.name, QUEST_COLOR) {
                prompt("Rename your quest:", q.name) { n ->
                    if (n.isNotBlank()) {
                        q.name = n.trim().take(40)
                        save.save()
                    }
                }
            }
        }
        opts += MenuOption("Abandon", q.name, QUEST_COLOR) {
            save.quests.remove(q)
            save.save()
            message("You abandon ${q.name}. It probably wasn't important.")
        }
        ui.showMenu(opts, x, y)
    }

    fun newQuest() = prompt("What is the name of your new quest?") { n ->
        if (n.isNotBlank()) {
            val q = Quest("u" + SystemClock.elapsedRealtime(), n.trim().take(40), Quest.STARTED, false)
            save.quests += q
            save.save()
            message("You have started a new quest: <col=ef1020>${q.name}</col>")
        }
    }

    // ------------------------------------------------------------------ side panel actions

    fun orbTapped(i: Int) {
        when (i) {
            0 -> actions.settings(Intent.ACTION_POWER_USAGE_SUMMARY)
            1 -> quickPrayer()
            2 -> actions.internetPanel()
            3 -> actions.showVolume()
        }
    }

    fun quickPrayer() {
        if (!actions.hasTorch()) {
            message("You don't have a light source.")
        } else if (!actions.toggleTorch()) {
            message("You have run out of prayer points, you can recharge at an altar.")
        }
        ui.invalidateAll()
    }

    fun playerClicked() {
        ui.overhead(Content.playerQuotes.random())
    }

    fun groundMenu(x: Float, y: Float) {
        haptic()
        ui.showMenu(
            listOf(
                MenuOption("Walk here"),
                MenuOption("Change", "Wallpaper", ITEM_CYAN) { actions.wallpaper() },
                MenuOption("Open", "Launcher settings", ITEM_CYAN) { ui.selectTab(SETTINGS_TAB) },
                MenuOption("Open", "Android settings", ITEM_CYAN) { actions.settings(Settings.ACTION_SETTINGS) },
            ),
            x, y,
        )
    }

    fun setIconStyle(style: Int) {
        save.iconStyle = style
        icons.pixelSize = pixelSizeFor(style)
        save.save()
        ui.invalidateAll()
    }

    fun castSpell(name: String, xp: Int, action: () -> Boolean) {
        ui.emote("CAST")
        if (action()) {
            award(Skill.MAGIC, xp)
            questEvent("spell")
            save.save()
        } else {
            message("You can't cast $name here. (No app can handle it.)")
        }
    }

    fun logout() {
        dialogues.addFirst(
            Dialogue.Options(
                "Log out? (This opens your home app settings.)",
                listOf(
                    "Yes." to {
                        questEvent("logout")
                        actions.homeSettings()
                    },
                    "No." to { message("You can't log out until 10 seconds after the end of combat.") },
                ),
            )
        )
        ui.invalidateAll()
    }

    fun playTrack(i: Int) {
        if (i >= unlockedTracks()) {
            message("You have not unlocked this piece of music yet!")
            return
        }
        nowPlaying = i
        actions.media(KeyEvent.KEYCODE_MEDIA_PLAY)
        ui.invalidateAll()
    }

    fun mediaKey(code: Int) {
        actions.media(code)
        val n = max(1, unlockedTracks())
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> nowPlaying = (nowPlaying + 1) % n
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> nowPlaying = (nowPlaying - 1 + n) % n
        }
        ui.invalidateAll()
    }

    companion object {
        const val TICK_MS = 600L
        const val PET_CHANCE = 1000
        const val INVENTORY_TAB = 3
        const val SETTINGS_TAB = 11
        const val QUEST_COLOR = 0xFFEF1020.toInt()
        const val ITEM_CYAN = 0xFF00FFFF.toInt()

        fun pixelSizeFor(style: Int) = when (style) {
            0 -> 26
            1 -> 40
            else -> 0
        }
    }
}
