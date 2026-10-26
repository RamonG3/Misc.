package com.ramong.runelauncher.ui.panels

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import com.ramong.runelauncher.core.Content
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.MenuOption
import com.ramong.runelauncher.core.Skill
import com.ramong.runelauncher.ui.Emote
import com.ramong.runelauncher.ui.Panel
import com.ramong.runelauncher.ui.Rs
import com.ramong.runelauncher.ui.SidePanelView
import com.ramong.runelauncher.ui.Sprite
import com.ramong.runelauncher.ui.Sprites
import java.util.Date
import kotlin.math.ceil
import kotlin.math.min

/** A grid of icon + caption cells, shared by the prayer and magic books. */
private fun gridCell(i: Int, cols: Int, r: RectF, top: Float, cellH: Float, out: RectF): RectF {
    val cw = r.width() / cols
    val col = i % cols
    val row = i / cols
    out.set(r.left + col * cw, top + row * cellH, r.left + (col + 1) * cw, top + (row + 1) * cellH)
    return out
}

class PrayerDef(val name: String, val label: String, val sprite: () -> Sprite, val action: (Game) -> Unit, val active: (Game) -> Boolean = { false })

/** Quick settings, disguised as the prayer book. Protect from Darkness is the flashlight. */
class PrayerPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val prayers = listOf(
        PrayerDef("Protect from Darkness", "Flashlight", { Sprites.FIREMAKING }, { it.quickPrayer() }, { it.actions.torchOn }),
        PrayerDef("Rapid Heal", "Battery", { Sprites.HITPOINTS }, { it.actions.settings(Intent.ACTION_POWER_USAGE_SUMMARY) }),
        PrayerDef("Sharp Eye", "Display", { Sprites.EYE }, { it.actions.settings(Settings.ACTION_DISPLAY_SETTINGS) }),
        PrayerDef("Mystic Lore", "Internet", { Sprites.MAGIC }, { it.actions.internetPanel() }),
        PrayerDef("Protect from Missiles", "Bluetooth", { Sprites.RANGED }, { it.actions.settings(Settings.ACTION_BLUETOOTH_SETTINGS) }),
        PrayerDef("Protect from Melee", "Do Not Disturb", { Sprites.DEFENCE }, { it.actions.settings("android.settings.ZEN_MODE_SETTINGS") }),
        PrayerDef("Hawk Eye", "Location", { Sprites.HUNTER }, { it.actions.settings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }),
        PrayerDef("Rapid Restore", "Sound", { Sprites.NOTE }, { it.actions.settings(Settings.ACTION_SOUND_SETTINGS) }),
        PrayerDef("Redemption", "Airplane", { Sprites.PRAYER }, { it.actions.settings(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }),
        PrayerDef("Preserve", "Storage", { Sprites.BAG }, { it.actions.settings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) }),
        PrayerDef("Smite", "Notifications", { Sprites.SLAYER }, { it.actions.settings("android.settings.NOTIFICATION_SETTINGS") }),
        PrayerDef("Steel Skin", "Security", { Sprites.HELMET }, { it.actions.settings(Settings.ACTION_SECURITY_SETTINGS) }),
        PrayerDef("Chivalry", "Accessibility", { Sprites.STRENGTH }, { it.actions.settings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
        PrayerDef("Augury", "Alarms", { Sprites.FARMING }, { it.actions.alarms() }),
        PrayerDef("Thick Skin", "Apps", { Sprites.SMITHING }, { it.actions.settings(Settings.ACTION_APPLICATION_SETTINGS) }),
        PrayerDef("Piety", "All Settings", { Sprites.GEAR }, { it.actions.settings(Settings.ACTION_SETTINGS) }),
    )
    private val cell = RectF()
    private val caption = Rs.paint(10.5f, align = Paint.Align.CENTER)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x70FFF0A0 }

    override val scrollable = true
    private fun cellH() = Rs.dp(66f)
    override fun contentHeight(r: RectF) = Rs.dp(8f) + ceil(prayers.size / 4f) * cellH() + Rs.dp(40f)

    override fun draw(c: Canvas, r: RectF) {
        val top = r.top + Rs.dp(8f)
        prayers.forEachIndexed { i, p ->
            val b = gridCell(i, 4, r, top, cellH(), cell)
            val icon = min(b.width(), b.height()) * 0.5f
            val cy = b.top + b.height() * 0.38f
            if (p.active(game)) c.drawCircle(b.centerX(), cy, icon * 0.75f, glow)
            p.sprite().draw(c, b.centerX(), cy, icon)
            Rs.text(c, Rs.ellipsize(p.label, caption, b.width() - Rs.dp(2f)), b.centerX(), b.bottom - Rs.dp(8f), caption, Rs.ORANGE)
        }
        val y = top + ceil(prayers.size / 4f) * cellH() + Rs.dp(24f)
        val s = game.stats
        Rs.text(c, String.format(java.util.Locale.US, "Prayer: %.0f / %.0f", s.storageFreeGb, s.storageTotalGb), r.centerX(), y, bodyCenter, Rs.ORANGE)
    }

    private fun indexAt(x: Float, y: Float, r: RectF): Int {
        val top = r.top + Rs.dp(8f)
        if (y < top) return -1
        val i = ((y - top) / cellH()).toInt() * 4 + ((x - r.left) / (r.width() / 4)).toInt().coerceIn(0, 3)
        return if (i in prayers.indices) i else -1
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val i = indexAt(x, y, r)
        if (i < 0) return
        game.haptic()
        prayers[i].action(game)
    }

    override fun longPress(x: Float, y: Float, r: RectF) {
        val i = indexAt(x, y, r)
        if (i < 0) return
        val p = prayers[i]
        game.ui.showMenu(listOf(MenuOption("Activate", p.name, Rs.ITEM) { p.action(game) }), menuX(x), menuY(y))
    }
}

class SpellDef(val name: String, val label: String, val level: Int, val color: Int, val xp: Int, val cast: (Game) -> Boolean)

/** The standard spellbook. Teleports open your default apps; casting trains Magic. */
class MagicPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val spells = listOf(
        SpellDef("Lumbridge Home Teleport", "Home", 0, 0xFF8EC4F0.toInt(), 0) { g ->
            g.ui.selectTab(Game.INVENTORY_TAB)
            true
        },
        SpellDef("Wind Strike", "Assistant", 1, 0xFFD8F4F4.toInt(), 5) { it.actions.assistant() },
        SpellDef("Enchant Sapphire", "Music", 7, 0xFF3060E0.toInt(), 17) { it.actions.category(Intent.CATEGORY_APP_MUSIC) },
        SpellDef("Bones to Bananas", "Gallery", 15, 0xFFF0E060.toInt(), 25) { it.actions.category(Intent.CATEGORY_APP_GALLERY) },
        SpellDef("Varrock Teleport", "Browser", 25, 0xFF4A7BD8.toInt(), 35) { it.actions.category(Intent.CATEGORY_APP_BROWSER) },
        SpellDef("Lumbridge Teleport", "Maps", 31, 0xFF5CB85C.toInt(), 41) { it.actions.maps() },
        SpellDef("Telekinetic Grab", "Camera", 33, 0xFF6AD8D8.toInt(), 43) { it.actions.camera() },
        SpellDef("Falador Teleport", "Email", 37, 0xFFE8E8E8.toInt(), 48) { it.actions.category(Intent.CATEGORY_APP_EMAIL) },
        SpellDef("Teleport to House", "Files", 40, 0xFFB08050.toInt(), 30) { it.actions.category(Intent.CATEGORY_APP_FILES) },
        SpellDef("Superheat Item", "Store", 43, 0xFFE05030.toInt(), 53) { it.actions.category(Intent.CATEGORY_APP_MARKET) },
        SpellDef("Camelot Teleport", "Messages", 45, 0xFFD8C84A.toInt(), 55) { it.actions.category(Intent.CATEGORY_APP_MESSAGING) },
        SpellDef("Ardougne Teleport", "Phone", 51, 0xFFD87A4A.toInt(), 61) { it.actions.dial() },
        SpellDef("High Level Alchemy", "Calculator", 55, 0xFFF0B030.toInt(), 65) { it.actions.category(Intent.CATEGORY_APP_CALCULATOR) },
        SpellDef("Watchtower Teleport", "Calendar", 58, 0xFF9A6AD8.toInt(), 68) { it.actions.calendar() },
        SpellDef("Trollheim Teleport", "Contacts", 61, 0xFF8A8A8A.toInt(), 68) { it.actions.category(Intent.CATEGORY_APP_CONTACTS) },
        SpellDef("Charge Air Orb", "Alarms", 66, 0xFFB0E0FF.toInt(), 76) { it.actions.alarms() },
    )
    private val cell = RectF()
    private val caption = Rs.paint(10.5f, align = Paint.Align.CENTER)
    private val levelPaint = Rs.paint(9.5f, align = Paint.Align.RIGHT)
    private val orb = Paint(Paint.ANTI_ALIAS_FLAG)

    override val scrollable = true
    private fun cellH() = Rs.dp(66f)
    override fun contentHeight(r: RectF) = Rs.dp(8f) + ceil(spells.size / 4f) * cellH() + Rs.dp(36f)

    private fun drawOrb(c: Canvas, cx: Float, cy: Float, rad: Float, color: Int) {
        orb.color = Rs.BLACK
        c.drawCircle(cx, cy, rad + Rs.px, orb)
        orb.color = Rs.shade(color, -80)
        c.drawCircle(cx, cy, rad, orb)
        orb.color = color
        c.drawCircle(cx - rad * 0.08f, cy - rad * 0.08f, rad * 0.78f, orb)
        orb.color = Rs.shade(color, 70)
        c.drawCircle(cx - rad * 0.28f, cy - rad * 0.28f, rad * 0.36f, orb)
        orb.color = 0xD0FFFFFF.toInt()
        c.drawCircle(cx - rad * 0.36f, cy - rad * 0.36f, rad * 0.14f, orb)
        orb.color = 0xB0FFFFFF.toInt()
        val s = rad * 0.55f
        c.drawRect(cx + rad * 0.35f - Rs.px, cy + rad * 0.3f - s / 2, cx + rad * 0.35f + Rs.px, cy + rad * 0.3f + s / 2, orb)
        c.drawRect(cx + rad * 0.35f - s / 2, cy + rad * 0.3f - Rs.px, cx + rad * 0.35f + s / 2, cy + rad * 0.3f + Rs.px, orb)
    }

    override fun draw(c: Canvas, r: RectF) {
        val top = r.top + Rs.dp(8f)
        spells.forEachIndexed { i, s ->
            val b = gridCell(i, 4, r, top, cellH(), cell)
            val rad = min(b.width(), b.height()) * 0.24f
            drawOrb(c, b.centerX(), b.top + b.height() * 0.38f, rad, s.color)
            if (s.level > 0) Rs.text(c, "${s.level}", b.right - Rs.dp(5f), b.top + Rs.dp(12f), levelPaint, 0xFFB0A080.toInt())
            Rs.text(c, Rs.ellipsize(s.label, caption, b.width() - Rs.dp(2f)), b.centerX(), b.bottom - Rs.dp(8f), caption, Rs.ORANGE)
        }
        val y = top + ceil(spells.size / 4f) * cellH() + Rs.dp(20f)
        Rs.text(c, "Magic level: ${game.level(Skill.MAGIC)}", r.centerX(), y, bodyCenter, Rs.ORANGE)
    }

    private fun indexAt(x: Float, y: Float, r: RectF): Int {
        val top = r.top + Rs.dp(8f)
        if (y < top) return -1
        val i = ((y - top) / cellH()).toInt() * 4 + ((x - r.left) / (r.width() / 4)).toInt().coerceIn(0, 3)
        return if (i in spells.indices) i else -1
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val i = indexAt(x, y, r)
        if (i < 0) return
        game.haptic()
        val s = spells[i]
        game.castSpell(s.name, s.xp) { s.cast(game) }
    }

    override fun longPress(x: Float, y: Float, r: RectF) {
        val i = indexAt(x, y, r)
        if (i < 0) return
        val s = spells[i]
        game.ui.showMenu(
            listOf(MenuOption("Cast", s.name, 0xFF00FF00.toInt()) { game.castSpell(s.name, s.xp) { s.cast(game) } }),
            menuX(x), menuY(y),
        )
    }
}

/** Your apps, grouped by the skill they train. Tap a row to open the bank on that skill. */
class ClanPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val rowH get() = Rs.dp(32f)
    private val name = Rs.paint(13.5f)
    private val count = Rs.paint(13.5f, align = Paint.Align.RIGHT)

    override val scrollable = true

    private fun rows() = Skill.values().map { it to game.appsTraining(it) }.filter { it.second > 0 }.sortedByDescending { it.second }
    private fun listTop(r: RectF) = r.top + Rs.dp(52f)
    override fun contentHeight(r: RectF) = listTop(r) - r.top + rows().size * rowH + Rs.dp(10f)

    override fun draw(c: Canvas, r: RectF) {
        val y = header(c, r, "Clan Hall")
        Rs.text(c, "Members: ${game.apps.size}", r.centerX(), y + Rs.dp(6f), bodyCenter, Rs.WHITE)
        var top = listTop(r)
        for ((skill, n) in rows()) {
            val icon = rowH * 0.62f
            Sprites.skill(skill).draw(c, r.left + Rs.dp(10f) + icon / 2f, top + rowH / 2f, icon)
            val by = Rs.baseline(name, top, top + rowH)
            Rs.text(c, skill.title, r.left + Rs.dp(18f) + icon, by, name, Rs.ORANGE)
            Rs.text(c, "$n", r.right - Rs.dp(10f), by, count, Rs.WHITE)
            top += rowH
        }
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        val i = ((y - listTop(r)) / rowH).toInt()
        val rows = rows()
        if (y >= listTop(r) && i in rows.indices) {
            game.haptic()
            game.ui.openBank(false, rows[i].first)
        }
    }
}

/** Recently used apps as a friends list. Online if you used them in the last day. */
class FriendsPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val rowH get() = Rs.dp(30f)
    private val name = Rs.paint(13.5f)
    private val world = Rs.paint(13.5f, align = Paint.Align.RIGHT)

    override val scrollable = true

    private fun friends() = game.save.lastUsed.entries.sortedByDescending { it.value }
        .mapNotNull { e -> game.app(e.key)?.let { it to e.value } }.take(40)

    private fun listTop(r: RectF) = r.top + Rs.dp(52f)
    override fun contentHeight(r: RectF) = listTop(r) - r.top + friends().size * rowH + Rs.dp(10f)

    override fun draw(c: Canvas, r: RectF) {
        val list = friends()
        val y = header(c, r, "Friends List")
        Rs.text(c, "${list.size} / 200", r.centerX(), y + Rs.dp(6f), bodyCenter, Rs.WHITE)
        var top = listTop(r)
        if (list.isEmpty()) {
            var ly = top + Rs.dp(20f)
            for (l in Rs.wrap("You have no friends. Open some apps; they're very friendly.", bodyCenter, r.width() - Rs.dp(24f))) {
                Rs.text(c, l, r.centerX(), ly, bodyCenter, Rs.ORANGE)
                ly += Rs.lineHeight(bodyCenter)
            }
            return
        }
        val now = System.currentTimeMillis()
        for ((app, last) in list) {
            val by = Rs.baseline(name, top, top + rowH)
            val online = now - last < 86_400_000L
            val status = if (online) "World ${301 + Math.floorMod(app.key.hashCode(), 250)}" else "Offline"
            val sw = world.measureText(status)
            Rs.text(c, Rs.ellipsize(app.label, name, r.width() - sw - Rs.dp(28f)), r.left + Rs.dp(10f), by, name, Rs.WHITE)
            Rs.text(c, status, r.right - Rs.dp(10f), by, world, if (online) Rs.YELLOW else Rs.RED)
            top += rowH
        }
    }

    private fun at(y: Float, r: RectF) = if (y < listTop(r)) -1 else ((y - listTop(r)) / rowH).toInt()

    override fun tap(x: Float, y: Float, r: RectF) {
        val list = friends()
        val i = at(y, r)
        if (i in list.indices) game.launch(list[i].first)
    }

    override fun longPress(x: Float, y: Float, r: RectF) {
        val list = friends()
        val i = at(y, r)
        if (i !in list.indices) return
        val app = list[i].first
        game.haptic()
        game.ui.showMenu(
            listOf(
                MenuOption("Open", app.label, Rs.WHITE) { game.launch(app) },
                MenuOption("Delete", app.label, Rs.WHITE) {
                    game.save.lastUsed.remove(app.key)
                    game.save.save()
                    game.ui.invalidateAll()
                },
            ),
            menuX(x), menuY(y),
        )
    }
}

/** Account info: your name, levels, "time played" (uptime) and the device. */
class AccountPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val label = Rs.paint(13f)
    private val value = Rs.paint(13f, align = Paint.Align.RIGHT)
    private val rowH get() = Rs.dp(26f)
    private val nameBtn = RectF()
    private val homeBtn = RectF()

    override val scrollable = true
    override fun contentHeight(r: RectF) = Rs.dp(40f) + 9 * rowH + Rs.dp(100f)

    private fun rows(): List<Pair<String, String>> {
        val up = SystemClock.elapsedRealtime() / 60_000L
        val played = "${up / 1440}d ${(up / 60) % 24}h ${up % 60}m"
        val since = DateFormat.format("d MMM yyyy", Date(game.save.installTime)).toString()
        return listOf(
            "Name" to game.save.name,
            "Combat level" to "${game.combatLevel()}",
            "Total level" to "${game.totalLevel()}",
            "Quest points" to "${game.questPoints()}",
            "Time played" to played,
            "Member since" to since,
            "World" to "${game.world}",
            "Device" to android.os.Build.MODEL,
            "Android" to android.os.Build.VERSION.RELEASE,
        )
    }

    override fun draw(c: Canvas, r: RectF) {
        var top = header(c, r, "Account Management") + Rs.dp(4f)
        for ((k, v) in rows()) {
            val by = Rs.baseline(label, top, top + rowH)
            Rs.text(c, k, r.left + Rs.dp(10f), by, label, Rs.ORANGE)
            Rs.text(c, Rs.ellipsize(v, value, r.width() * 0.55f), r.right - Rs.dp(10f), by, value, Rs.WHITE)
            top += rowH
        }
        top += Rs.dp(10f)
        nameBtn.set(r.left + Rs.dp(8f), top, r.right - Rs.dp(8f), top + Rs.dp(34f))
        homeBtn.set(nameBtn.left, nameBtn.bottom + Rs.dp(6f), nameBtn.right, nameBtn.bottom + Rs.dp(40f))
        Rs.button(c, nameBtn)
        Rs.text(c, "Name change", nameBtn.centerX(), Rs.baseline(bodyCenter, nameBtn.top, nameBtn.bottom), bodyCenter, Rs.ORANGE)
        Rs.button(c, homeBtn)
        Rs.text(c, "Choose default home app", homeBtn.centerX(), Rs.baseline(bodyCenter, homeBtn.top, homeBtn.bottom), bodyCenter, Rs.ORANGE)
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        if (nameBtn.contains(x, y)) game.askName()
        if (homeBtn.contains(x, y)) game.actions.homeSettings()
    }
}

class LogoutPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val logout = RectF()
    private val hop = RectF()
    private val big = Rs.paint(15f, bold = true, align = Paint.Align.CENTER)

    override fun draw(c: Canvas, r: RectF) {
        var y = r.top + Rs.dp(34f)
        val text = "When you have finished playing RuneScape, always use the button below to logout safely."
        for (l in Rs.wrap(text, bodyCenter, r.width() - Rs.dp(28f))) {
            Rs.text(c, l, r.centerX(), y, bodyCenter, Rs.ORANGE)
            y += Rs.lineHeight(bodyCenter)
        }
        y += Rs.dp(16f)
        logout.set(r.left + Rs.dp(20f), y, r.right - Rs.dp(20f), y + Rs.dp(48f))
        Rs.button(c, logout)
        Rs.text(c, "Click here to logout", logout.centerX(), Rs.baseline(big, logout.top, logout.bottom), big, Rs.ORANGE)
        hop.set(logout.left, logout.bottom + Rs.dp(14f), logout.right, logout.bottom + Rs.dp(52f))
        Rs.button(c, hop)
        Rs.text(c, "World Switcher (World ${game.world})", hop.centerX(), Rs.baseline(bodyCenter, hop.top, hop.bottom), bodyCenter, Rs.ORANGE)
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        if (logout.contains(x, y)) {
            game.haptic()
            game.logout()
        } else if (hop.contains(x, y)) {
            game.haptic()
            game.worldHop()
        }
    }
}

class SettingsPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private class Row(val label: String, val value: () -> String?, val color: () -> Int, val action: () -> Unit)

    private val label = Rs.paint(13f)
    private val value = Rs.paint(13f, align = Paint.Align.RIGHT)
    private val rowH get() = Rs.dp(38f)
    private val gap get() = Rs.dp(5f)
    private val cell = RectF()

    private fun onOff(b: Boolean) = if (b) "On" else "Off"
    private fun onOffColor(b: Boolean) = if (b) Rs.GREEN else Rs.RED

    private val rows: List<Row> = listOf(
        Row("Display name", { game.save.name }, { Rs.WHITE }) { game.askName() },
        Row("Item style", { arrayOf("Chunky", "Fine", "HD")[game.save.iconStyle] }, { Rs.WHITE }) {
            game.setIconStyle((game.save.iconStyle + 1) % 3)
        },
        Row("Inventory names", { onOff(game.save.invLabels) }, { onOffColor(game.save.invLabels) }) { toggle { game.save.invLabels = !game.save.invLabels } },
        Row("XP drops", { onOff(game.save.xpDrops) }, { onOffColor(game.save.xpDrops) }) { toggle { game.save.xpDrops = !game.save.xpDrops } },
        Row("Show player", { onOff(game.save.showPlayer) }, { onOffColor(game.save.showPlayer) }) { toggle { game.save.showPlayer = !game.save.showPlayer } },
        Row("24-hour clock", { onOff(game.save.clock24) }, { onOffColor(game.save.clock24) }) { toggle { game.save.clock24 = !game.save.clock24 } },
        Row("Haptics", { onOff(game.save.haptics) }, { onOffColor(game.save.haptics) }) { toggle { game.save.haptics = !game.save.haptics } },
        Row("Bank sort", { arrayOf("A-Z", "Most used", "By skill")[game.save.bankSort] }, { Rs.WHITE }) {
            toggle { game.save.bankSort = (game.save.bankSort + 1) % 3 }
            game.ui.bankRefresh()
        },
        Row("Change wallpaper", { null }, { Rs.WHITE }) { game.actions.wallpaper() },
        Row("Default home app", { null }, { Rs.WHITE }) { game.actions.homeSettings() },
        Row("Android settings", { null }, { Rs.WHITE }) { game.actions.settings(Settings.ACTION_SETTINGS) },
        Row("Reset XP", { null }, { Rs.WHITE }) { game.confirmReset() },
    )

    private fun toggle(f: () -> Unit) {
        f()
        game.save.save()
        game.ui.invalidateAll()
    }

    override val scrollable = true
    override fun contentHeight(r: RectF) = Rs.dp(40f) + rows.size * (rowH + gap) + Rs.dp(8f)

    private fun rowRect(i: Int, r: RectF): RectF {
        val top = r.top + Rs.dp(40f) + i * (rowH + gap)
        cell.set(r.left + Rs.dp(4f), top, r.right - Rs.dp(4f), top + rowH)
        return cell
    }

    override fun draw(c: Canvas, r: RectF) {
        header(c, r, "Settings")
        rows.forEachIndexed { i, row ->
            val b = rowRect(i, r)
            Rs.button(c, b)
            val by = Rs.baseline(label, b.top, b.bottom)
            Rs.text(c, row.label, b.left + Rs.dp(10f), by, label, if (row.label == "Reset XP") Rs.RED else Rs.ORANGE)
            row.value()?.let { Rs.text(c, Rs.ellipsize(it, value, b.width() * 0.45f), b.right - Rs.dp(10f), by, value, row.color()) }
        }
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        for (i in rows.indices) {
            if (rowRect(i, r).contains(x, y)) {
                game.haptic()
                rows[i].action()
                return
            }
        }
    }
}

class EmotesPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val emotes = Emote.menu
    private val cell = RectF()
    private val label = Rs.paint(12f, align = Paint.Align.CENTER)
    private val rowH get() = Rs.dp(42f)
    private val gap get() = Rs.dp(4f)

    override val scrollable = true
    override fun contentHeight(r: RectF) = ceil(emotes.size / 3f) * (rowH + gap) + Rs.dp(8f)

    private fun rect(i: Int, r: RectF): RectF {
        val cw = (r.width() - gap * 2) / 3f
        val col = i % 3
        val row = i / 3
        val l = r.left + col * (cw + gap)
        val t = r.top + Rs.dp(4f) + row * (rowH + gap)
        cell.set(l, t, l + cw, t + rowH)
        return cell
    }

    override fun draw(c: Canvas, r: RectF) {
        emotes.forEachIndexed { i, e ->
            val b = rect(i, r)
            Rs.button(c, b)
            Rs.text(c, e.label, b.centerX(), Rs.baseline(label, b.top, b.bottom), label, Rs.ORANGE)
        }
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        for (i in emotes.indices) {
            if (rect(i, r).contains(x, y)) {
                game.haptic()
                game.ui.emote(emotes[i].name)
                game.questEvent("emote")
                return
            }
        }
    }
}

/** Media controls for whatever is playing, wearing the music player's track list. */
class MusicPanel(game: Game, host: SidePanelView) : Panel(game, host) {
    private val buttons = Array(4) { RectF() }
    private val rowH get() = Rs.dp(25f)
    private val track = Rs.paint(13.5f, align = Paint.Align.CENTER)
    private val path = Path()
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)

    override val scrollable = true
    private fun listTop(r: RectF) = r.top + Rs.dp(132f)
    override fun contentHeight(r: RectF) = listTop(r) - r.top + Content.tracks.size * rowH + Rs.dp(10f)

    override fun draw(c: Canvas, r: RectF) {
        val y = header(c, r, "Music Player")
        Rs.text(c, "Now playing:", r.centerX(), y + Rs.dp(8f), bodyCenter, Rs.ORANGE)
        Rs.text(c, Content.tracks[game.nowPlaying], r.centerX(), y + Rs.dp(26f), track, Rs.GREEN)
        val bw = (r.width() - Rs.dp(8f) - Rs.dp(18f)) / 4f
        val top = y + Rs.dp(38f)
        for (i in 0 until 4) {
            val l = r.left + Rs.dp(4f) + i * (bw + Rs.dp(6f))
            buttons[i].set(l, top, l + bw, top + Rs.dp(36f))
            Rs.button(c, buttons[i])
            drawControl(c, i, buttons[i])
        }
        Rs.text(c, "Unlocked: ${game.unlockedTracks()} / ${Content.tracks.size}", r.centerX(), listTop(r) - Rs.dp(8f), small, Rs.WHITE)
        var t = listTop(r)
        val unlocked = game.unlockedTracks()
        Content.tracks.forEachIndexed { i, name ->
            Rs.text(c, name, r.centerX(), Rs.baseline(track, t, t + rowH), track, if (i < unlocked) Rs.GREEN else Rs.RED)
            t += rowH
        }
    }

    private fun drawControl(c: Canvas, i: Int, b: RectF) {
        val cx = b.centerX()
        val cy = b.centerY()
        val s = b.height() * 0.22f
        fun tri(x: Float, dir: Float) {
            path.reset()
            path.moveTo(x - s * 0.6f * dir, cy - s)
            path.lineTo(x + s * 0.6f * dir, cy)
            path.lineTo(x - s * 0.6f * dir, cy + s)
            path.close()
            c.save()
            c.translate(Rs.shadowOf(track), Rs.shadowOf(track))
            icon.color = Rs.BLACK
            c.drawPath(path, icon)
            c.restore()
            icon.color = Rs.WHITE
            c.drawPath(path, icon)
        }
        when (i) {
            0 -> {
                tri(cx - s * 0.2f, -1f)
                tri(cx + s * 0.9f, -1f)
            }
            1 -> {
                tri(cx - s * 0.5f, 1f)
                Rs.rect(c, cx + s * 0.4f, cy - s, cx + s * 0.7f, cy + s, Rs.WHITE)
                Rs.rect(c, cx + s * 0.95f, cy - s, cx + s * 1.25f, cy + s, Rs.WHITE)
            }
            2 -> {
                tri(cx - s * 0.9f, 1f)
                tri(cx + s * 0.2f, 1f)
            }
            else -> Sprites.NOTE.draw(c, cx, cy, b.height() * 0.6f)
        }
    }

    override fun tap(x: Float, y: Float, r: RectF) {
        when {
            buttons[0].contains(x, y) -> game.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            buttons[1].contains(x, y) -> game.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            buttons[2].contains(x, y) -> game.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            buttons[3].contains(x, y) -> if (!game.actions.category(Intent.CATEGORY_APP_MUSIC)) game.message("You don't have a music player. Tragic.")
            y >= listTop(r) -> {
                val i = ((y - listTop(r)) / rowH).toInt()
                if (i in Content.tracks.indices) game.playTrack(i)
            }
            else -> return
        }
        game.haptic()
    }
}
