package com.ramong.cyberlauncher.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything the launcher remembers, kept in one SharedPreferences file. */
class SaveData(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cyberlauncher", Context.MODE_PRIVATE)

    var name = "V"
    /** Lifepath ordinal, or -1 until one has been picked. */
    var lifepath = -1
    var theme = "street"
    val owned = HashSet<String>()
    /** The home screen grid, row by row. (Stored under its old name, "inventory".) */
    val home = arrayOfNulls<String>(HOME)
    val dock = arrayOfNulls<String>(DOCK)
    /** False until the dock has been filled once, from old cyberware slots or defaults. */
    var dockReady = false
    val launches = HashMap<String, Int>()
    val lastUsed = HashMap<String, Long>()
    /** User tasks, plus the built-in jobs, which are now hidden achievements. */
    val jobs = ArrayList<Job>()

    var xp = 0L
    val attrXp = LongArray(Attr.values().size)
    var cred = 0L
    var eddies = 0L
    var totalLaunches = 0L
    var hacks = 0

    /** 0 = stock icons, 1 = chrome (scanlines + colour fringe), 2 = holo (tinted to the theme). */
    var iconStyle = 1
    var labels = true
    var rarityGlow = true
    var clock24 = true
    var realYear = false
    var rain = true
    var glitch = true
    var boot = true
    var haptics = true
    /** Show the deck page to the left of home. */
    var deck = true
    /** 0 = draw the city, 1 = show the phone's wallpaper. */
    var scene = 0
    var station = 0
    var hasCat = false
    var initialized = false
    var installTime = 0L

    init {
        load()
    }

    private fun load() {
        name = sp.getString("name", null) ?: "V"
        lifepath = sp.getInt("lifepath", -1)
        theme = sp.getString("theme", null) ?: "street"
        sp.getString("owned", null)?.let { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) owned += a.optString(i)
        }
        readArray("inventory", home)
        dockReady = sp.getBoolean("dockReady", false)
        readArray("dock", dock)
        if (!dockReady) migrateDock()
        sp.getString("launches", null)?.let { s ->
            val o = JSONObject(s)
            o.keys().forEach { k -> launches[k] = o.optInt(k) }
        }
        sp.getString("lastUsed", null)?.let { s ->
            val o = JSONObject(s)
            o.keys().forEach { k -> lastUsed[k] = o.optLong(k) }
        }
        sp.getString("jobs", null)?.let { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                jobs += Job(
                    o.getString("id"), o.optString("title"), o.optString("note"), o.optBoolean("done"),
                    o.optBoolean("main"), o.optLong("created"),
                )
            }
        }
        xp = sp.getLong("xp", 0L)
        sp.getString("attrXp", null)?.let { s ->
            val o = JSONObject(s)
            for (a in Attr.values()) attrXp[a.ordinal] = o.optLong(a.name, 0L)
        }
        cred = sp.getLong("cred", 0L)
        eddies = sp.getLong("eddies", 0L)
        totalLaunches = sp.getLong("totalLaunches", 0L)
        hacks = sp.getInt("hacks", 0)
        iconStyle = sp.getInt("iconStyle", 1)
        labels = sp.getBoolean("labels", true)
        rarityGlow = sp.getBoolean("rarityGlow", true)
        clock24 = sp.getBoolean("clock24", true)
        realYear = sp.getBoolean("realYear", false)
        rain = sp.getBoolean("rain", true)
        glitch = sp.getBoolean("glitch", true)
        boot = sp.getBoolean("boot", true)
        haptics = sp.getBoolean("haptics", true)
        deck = sp.getBoolean("deck", true)
        scene = sp.getInt("scene", 0)
        station = sp.getInt("station", 0)
        hasCat = sp.getBoolean("hasCat", false)
        initialized = sp.getBoolean("initialized", false)
        installTime = sp.getLong("installTime", 0L)
    }

    /** The first version had ten "cyberware" slots instead of a dock; keep the first five. */
    private fun migrateDock() {
        val old = arrayOfNulls<String>(10)
        readArray("cyberware", old)
        // Slots in the order the old launcher filled them: OS, cortex, optics, hands, arms...
        val order = intArrayOf(5, 0, 1, 8, 4, 3, 2, 6, 7, 9)
        var d = 0
        for (i in order) {
            val k = old[i] ?: continue
            if (d < DOCK && dock.none { it == k }) dock[d++] = k
        }
        if (d > 0) dockReady = true
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
        val attrJson = JSONObject()
        for (a in Attr.values()) attrJson.put(a.name, attrXp[a.ordinal])
        val jobJson = JSONArray()
        jobs.forEach {
            jobJson.put(
                JSONObject().put("id", it.id).put("title", it.title).put("note", it.note).put("done", it.done)
                    .put("main", it.main).put("created", it.created)
            )
        }
        sp.edit()
            .putString("name", name)
            .putInt("lifepath", lifepath)
            .putString("theme", theme)
            .putString("owned", JSONArray(owned.toList()).toString())
            .putString("inventory", writeArray(home))
            .putString("dock", writeArray(dock))
            .putBoolean("dockReady", dockReady)
            .putString("launches", JSONObject(launches as Map<*, *>).toString())
            .putString("lastUsed", JSONObject(lastUsed as Map<*, *>).toString())
            .putString("jobs", jobJson.toString())
            .putLong("xp", xp)
            .putString("attrXp", attrJson.toString())
            .putLong("cred", cred)
            .putLong("eddies", eddies)
            .putLong("totalLaunches", totalLaunches)
            .putInt("hacks", hacks)
            .putInt("iconStyle", iconStyle)
            .putBoolean("labels", labels)
            .putBoolean("rarityGlow", rarityGlow)
            .putBoolean("clock24", clock24)
            .putBoolean("realYear", realYear)
            .putBoolean("rain", rain)
            .putBoolean("glitch", glitch)
            .putBoolean("boot", boot)
            .putBoolean("haptics", haptics)
            .putBoolean("deck", deck)
            .putInt("scene", scene)
            .putInt("station", station)
            .putBoolean("hasCat", hasCat)
            .putBoolean("initialized", initialized)
            .putLong("installTime", installTime)
            .apply()
    }

    companion object {
        const val HOME = 20
        const val HOME_COLS = 4
        const val DOCK = 5
    }
}
