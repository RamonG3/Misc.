package com.ramong.cyberlauncher.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The ten cyberware slots, five down each side of the ripperdoc diagram. */
enum class Ware(val title: String, val short: String) {
    CORTEX("Frontal Cortex", "CORTEX"),
    OCULAR("Ocular System", "OPTICS"),
    CIRCULATORY("Circulatory System", "BLOOD"),
    NERVOUS("Nervous System", "NERVES"),
    ARMS("Arms", "ARMS"),
    OS("Operating System", "OS"),
    SKIN("Integumentary System", "SKIN"),
    SKELETON("Skeleton", "BONES"),
    HANDS("Hands", "HANDS"),
    LEGS("Legs", "LEGS"),
}

/** Everything the launcher remembers, kept in one SharedPreferences file. */
class SaveData(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cyberlauncher", Context.MODE_PRIVATE)

    var name = "V"
    /** Lifepath ordinal, or -1 until one has been picked. */
    var lifepath = -1
    var theme = "street"
    val owned = HashSet<String>()
    val inventory = arrayOfNulls<String>(INVENTORY)
    val cyberware = arrayOfNulls<String>(Ware.values().size)
    val launches = HashMap<String, Int>()
    val lastUsed = HashMap<String, Long>()
    val jobs = ArrayList<Job>()
    var tracked: String? = null

    var xp = 0L
    val attrXp = LongArray(Attr.values().size)
    var cred = 0L
    var eddies = 0L
    var totalLaunches = 0L
    var hacks = 0

    /** 0 = stock icons, 1 = chrome (scanlines + colour fringe), 2 = holo (tinted to the theme). */
    var iconStyle = 1
    var labels = true
    var clock24 = true
    var realYear = false
    var rain = true
    var glitch = true
    var boot = true
    var haptics = true
    /** 0 = draw the city, 1 = show the phone's wallpaper behind the HUD. */
    var scene = 0
    var stashSort = 0
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
        readArray("inventory", inventory)
        readArray("cyberware", cyberware)
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
        tracked = sp.getString("tracked", null)
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
        clock24 = sp.getBoolean("clock24", true)
        realYear = sp.getBoolean("realYear", false)
        rain = sp.getBoolean("rain", true)
        glitch = sp.getBoolean("glitch", true)
        boot = sp.getBoolean("boot", true)
        haptics = sp.getBoolean("haptics", true)
        scene = sp.getInt("scene", 0)
        stashSort = sp.getInt("stashSort", 0)
        station = sp.getInt("station", 0)
        hasCat = sp.getBoolean("hasCat", false)
        initialized = sp.getBoolean("initialized", false)
        installTime = sp.getLong("installTime", 0L)
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
            .putString("inventory", writeArray(inventory))
            .putString("cyberware", writeArray(cyberware))
            .putString("launches", JSONObject(launches as Map<*, *>).toString())
            .putString("lastUsed", JSONObject(lastUsed as Map<*, *>).toString())
            .putString("jobs", jobJson.toString())
            .putString("tracked", tracked)
            .putLong("xp", xp)
            .putString("attrXp", attrJson.toString())
            .putLong("cred", cred)
            .putLong("eddies", eddies)
            .putLong("totalLaunches", totalLaunches)
            .putInt("hacks", hacks)
            .putInt("iconStyle", iconStyle)
            .putBoolean("labels", labels)
            .putBoolean("clock24", clock24)
            .putBoolean("realYear", realYear)
            .putBoolean("rain", rain)
            .putBoolean("glitch", glitch)
            .putBoolean("boot", boot)
            .putBoolean("haptics", haptics)
            .putInt("scene", scene)
            .putInt("stashSort", stashSort)
            .putInt("station", station)
            .putBoolean("hasCat", hasCat)
            .putBoolean("initialized", initialized)
            .putLong("installTime", installTime)
            .apply()
    }

    companion object {
        const val INVENTORY = 20
    }
}
