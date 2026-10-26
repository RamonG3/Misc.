package com.ramong.runelauncher.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class EquipSlot(val title: String) {
    HEAD("Head"), CAPE("Cape"), NECK("Neck"), AMMO("Ammunition"), WEAPON("Weapon"), BODY("Body"),
    SHIELD("Shield"), LEGS("Legs"), HANDS("Hands"), FEET("Feet"), RING("Ring")
}

class Quest(val id: String, var name: String, var state: Int, val builtin: Boolean) {
    companion object {
        const val NOT_STARTED = 0
        const val STARTED = 1
        const val DONE = 2
    }
}

/** Everything the launcher remembers, kept in one SharedPreferences file. */
class SaveData(ctx: Context) {
    private val sp = ctx.getSharedPreferences("runelauncher", Context.MODE_PRIVATE)

    var name = "Adventurer"
    val inventory = arrayOfNulls<String>(28)
    val equipment = arrayOfNulls<String>(EquipSlot.values().size)
    val xp = LongArray(Skill.values().size)
    val launches = HashMap<String, Int>()
    val lastUsed = HashMap<String, Long>()
    val quests = ArrayList<Quest>()

    /** 0 = chunky pixels (Punch), 1 = fine pixels (Kick), 2 = HD icons (Block). */
    var iconStyle = 0
    var xpDrops = true
    var invLabels = false
    var showPlayer = true
    var clock24 = true
    var haptics = true
    var hasPet = false
    var initialized = false
    var installTime = 0L
    var bankSort = 0

    init {
        load()
    }

    private fun load() {
        name = sp.getString("name", null) ?: "Adventurer"
        readArray("inventory", inventory)
        readArray("equipment", equipment)
        sp.getString("xp", null)?.let { s ->
            val o = JSONObject(s)
            for (skill in Skill.values()) xp[skill.ordinal] = o.optLong(skill.name, 0L)
        }
        if (xp[Skill.HITPOINTS.ordinal] < Xp.forLevel(10)) xp[Skill.HITPOINTS.ordinal] = Xp.forLevel(10)
        sp.getString("launches", null)?.let { s ->
            val o = JSONObject(s)
            o.keys().forEach { k -> launches[k] = o.optInt(k) }
        }
        sp.getString("lastUsed", null)?.let { s ->
            val o = JSONObject(s)
            o.keys().forEach { k -> lastUsed[k] = o.optLong(k) }
        }
        sp.getString("quests", null)?.let { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                quests += Quest(o.getString("id"), o.getString("name"), o.optInt("state"), o.optBoolean("builtin"))
            }
        }
        iconStyle = sp.getInt("iconStyle", 0)
        xpDrops = sp.getBoolean("xpDrops", true)
        invLabels = sp.getBoolean("invLabels", false)
        showPlayer = sp.getBoolean("showPlayer", true)
        clock24 = sp.getBoolean("clock24", true)
        haptics = sp.getBoolean("haptics", true)
        hasPet = sp.getBoolean("hasPet", false)
        initialized = sp.getBoolean("initialized", false)
        installTime = sp.getLong("installTime", 0L)
        bankSort = sp.getInt("bankSort", 0)
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
        val xpJson = JSONObject()
        for (skill in Skill.values()) xpJson.put(skill.name, xp[skill.ordinal])
        val questJson = JSONArray()
        quests.forEach {
            questJson.put(JSONObject().put("id", it.id).put("name", it.name).put("state", it.state).put("builtin", it.builtin))
        }
        sp.edit()
            .putString("name", name)
            .putString("inventory", writeArray(inventory))
            .putString("equipment", writeArray(equipment))
            .putString("xp", xpJson.toString())
            .putString("launches", JSONObject(launches as Map<*, *>).toString())
            .putString("lastUsed", JSONObject(lastUsed as Map<*, *>).toString())
            .putString("quests", questJson.toString())
            .putInt("iconStyle", iconStyle)
            .putBoolean("xpDrops", xpDrops)
            .putBoolean("invLabels", invLabels)
            .putBoolean("showPlayer", showPlayer)
            .putBoolean("clock24", clock24)
            .putBoolean("haptics", haptics)
            .putBoolean("hasPet", hasPet)
            .putBoolean("initialized", initialized)
            .putLong("installTime", installTime)
            .putInt("bankSort", bankSort)
            .apply()
    }
}
