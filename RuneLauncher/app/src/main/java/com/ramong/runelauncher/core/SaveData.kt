package com.ramong.runelauncher.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A home-screen folder. [items] are app keys, in the order they were added. */
class Folder(val id: String, var name: String, val items: MutableList<String>)

/** A to-do in the quest journal. */
class Quest(val id: String, var title: String, var done: Boolean, val created: Long)

/**
 * Everything the launcher remembers, kept in one SharedPreferences file.
 * Home and dock cells hold an app key, a folder reference ("@f:<id>") or null.
 */
class SaveData(ctx: Context) {
    private val sp = ctx.getSharedPreferences("runelauncher", Context.MODE_PRIVATE)

    var name = "Adventurer"
    var theme = "gold"
    val home = arrayOfNulls<String>(HOME)
    val dock = arrayOfNulls<String>(DOCK)
    val folders = LinkedHashMap<String, Folder>()
    val xp = LongArray(Skill.values().size)
    val launches = HashMap<String, Int>()
    val lastUsed = HashMap<String, Long>()
    val quests = ArrayList<Quest>()
    val achievements = HashSet<String>()
    var questPoints = 0

    /** 0 = stock icons, 1 = gilded (gold ring), 2 = Old School pixels. */
    var iconStyle = 1
    var labels = true
    var clock24 = true
    var xpDrops = true
    var haptics = true
    /** Show the Adventurer's Log page to the left of home. */
    var logPage = true
    /** 0 = draw Gielinor, 1 = show the phone's wallpaper. */
    var scene = 0
    var world = 84
    var place = 0
    var track = 0
    var initialized = false
    var installTime = 0L
    /** True if this save came over from the first (Old School) version. */
    var migrated = false

    init {
        load()
    }

    private fun load() {
        name = sp.getString("name", null) ?: "Adventurer"
        theme = sp.getString("theme", null) ?: "gold"
        sp.getString("launches", null)?.let { s ->
            val o = JSONObject(s)
            o.keys().forEach { k -> launches[k] = o.optInt(k) }
        }
        sp.getString("lastUsed", null)?.let { s ->
            val o = JSONObject(s)
            o.keys().forEach { k -> lastUsed[k] = o.optLong(k) }
        }
        if (sp.contains("v2home")) {
            readArray("v2home", home)
            readArray("v2dock", dock)
            sp.getString("v2folders", null)?.let { s ->
                val o = JSONObject(s)
                o.keys().forEach { id ->
                    val f = o.getJSONObject(id)
                    val items = ArrayList<String>()
                    val a = f.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until a.length()) items += a.optString(i)
                    folders[id] = Folder(id, f.optString("name", "Folder"), items)
                }
            }
            sp.getString("v2xp", null)?.let { s ->
                val o = JSONObject(s)
                for (sk in Skill.values()) xp[sk.ordinal] = o.optLong(sk.name, 0L)
            }
            sp.getString("v2quests", null)?.let { s ->
                val a = JSONArray(s)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    quests += Quest(o.getString("id"), o.optString("title"), o.optBoolean("done"), o.optLong("created"))
                }
            }
            sp.getString("v2ach", null)?.let { s ->
                val a = JSONArray(s)
                for (i in 0 until a.length()) achievements += a.optString(i)
            }
            questPoints = sp.getInt("v2qp", 0)
            iconStyle = sp.getInt("v2iconStyle", 1)
            migrated = sp.getBoolean("v2migrated", false)
        } else {
            migrateFromV1()
        }
        if (xp[Skill.CONSTITUTION.ordinal] < Xp.forLevel(10)) xp[Skill.CONSTITUTION.ordinal] = Xp.forLevel(10)
        labels = sp.getBoolean("labels", true)
        clock24 = sp.getBoolean("clock24", true)
        xpDrops = sp.getBoolean("xpDrops", true)
        haptics = sp.getBoolean("haptics", true)
        logPage = sp.getBoolean("logPage", true)
        scene = sp.getInt("scene", 0)
        world = sp.getInt("world", 84)
        place = sp.getInt("place", 0)
        track = sp.getInt("track", 0)
        initialized = sp.getBoolean("initialized", false)
        installTime = sp.getLong("installTime", 0L)
    }

    /**
     * The first version had a 28-slot inventory and 11 equipment slots. The first 19 inventory
     * items become the home grid (anything beyond that goes into a "Backpack" folder in the last
     * cell), equipment becomes the dock, and XP carries over skill by skill.
     */
    private fun migrateFromV1() {
        if (!sp.contains("inventory")) return
        migrated = true
        val inv = arrayOfNulls<String>(28)
        readArray("inventory", inv)
        val equip = arrayOfNulls<String>(11)
        readArray("equipment", equip)
        val items = inv.filterNotNull().distinct()
        val equipped = equip.filterNotNull().distinct().filter { it !in items }
        var d = 0
        for (k in equipped) if (d < DOCK) dock[d++] = k
        val overflow = ArrayList<String>()
        overflow += equipped.drop(d)
        items.forEachIndexed { i, k -> if (i < HOME - 1) home[i] = k else overflow += k }
        if (overflow.size == 1 && home[HOME - 1] == null) {
            home[HOME - 1] = overflow[0]
        } else if (overflow.isNotEmpty()) {
            val f = Folder("backpack", "Backpack", overflow)
            folders[f.id] = f
            home[HOME - 1] = FOLDER + f.id
        }
        sp.getString("xp", null)?.let { s ->
            val o = JSONObject(s)
            for (sk in Skill.values()) {
                val old = when (sk) {
                    Skill.CONSTITUTION -> "HITPOINTS"
                    Skill.RUNECRAFTING -> "RUNECRAFT"
                    else -> sk.name
                }
                xp[sk.ordinal] = o.optLong(old, 0L)
            }
        }
        sp.getString("quests", null)?.let { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optBoolean("builtin")) continue
                quests += Quest(o.getString("id"), o.optString("name"), o.optInt("state") == 2, System.currentTimeMillis())
            }
        }
    }

    private fun readArray(key: String, into: Array<String?>) {
        val s = sp.getString(key, null) ?: return
        val a = JSONArray(s)
        for (i in into.indices) into[i] = if (i < a.length() && !a.isNull(i)) a.optString(i) else null
    }

    private fun writeArray(a: Array<String?>): String {
        val j = JSONArray()
        a.forEach { j.put(it ?: JSONObject.NULL) }
        return j.toString()
    }

    fun save() {
        // Folders nobody points at any more are gone for good.
        val used = (home.toList() + dock.toList()).filterNotNull().filter { it.startsWith(FOLDER) }.map { it.removePrefix(FOLDER) }.toSet()
        folders.keys.retainAll(used)
        val xpJson = JSONObject()
        for (sk in Skill.values()) xpJson.put(sk.name, xp[sk.ordinal])
        val fJson = JSONObject()
        for (f in folders.values) fJson.put(f.id, JSONObject().put("name", f.name).put("items", JSONArray(f.items)))
        val qJson = JSONArray()
        quests.forEach { qJson.put(JSONObject().put("id", it.id).put("title", it.title).put("done", it.done).put("created", it.created)) }
        sp.edit()
            .putString("name", name)
            .putString("theme", theme)
            .putString("v2home", writeArray(home))
            .putString("v2dock", writeArray(dock))
            .putString("v2folders", fJson.toString())
            .putString("v2xp", xpJson.toString())
            .putString("v2quests", qJson.toString())
            .putString("v2ach", JSONArray(achievements.toList()).toString())
            .putInt("v2qp", questPoints)
            .putInt("v2iconStyle", iconStyle)
            .putBoolean("v2migrated", migrated)
            .putString("launches", JSONObject(launches as Map<*, *>).toString())
            .putString("lastUsed", JSONObject(lastUsed as Map<*, *>).toString())
            .putBoolean("labels", labels)
            .putBoolean("clock24", clock24)
            .putBoolean("xpDrops", xpDrops)
            .putBoolean("haptics", haptics)
            .putBoolean("logPage", logPage)
            .putInt("scene", scene)
            .putInt("world", world)
            .putInt("place", place)
            .putInt("track", track)
            .putBoolean("initialized", initialized)
            .putLong("installTime", installTime)
            .apply()
    }

    companion object {
        const val HOME = 20
        const val HOME_COLS = 4
        const val DOCK = 5
        const val FOLDER = "@f:"
    }
}
