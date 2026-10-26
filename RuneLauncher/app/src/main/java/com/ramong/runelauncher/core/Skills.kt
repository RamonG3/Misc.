package com.ramong.runelauncher.core

import android.content.pm.ApplicationInfo
import kotlin.math.floor
import kotlin.math.pow

/**
 * The 23 classic skills, in skills-tab order (read left to right, three per row).
 * [xpPerAction] is what one app launch is worth. Runecraft is slow on purpose.
 */
enum class Skill(val title: String, val xpPerAction: Int) {
    ATTACK("Attack", 40), HITPOINTS("Hitpoints", 13), MINING("Mining", 35),
    STRENGTH("Strength", 40), AGILITY("Agility", 30), SMITHING("Smithing", 37),
    DEFENCE("Defence", 40), HERBLORE("Herblore", 50), FISHING("Fishing", 50),
    RANGED("Ranged", 40), THIEVING("Thieving", 26), COOKING("Cooking", 30),
    PRAYER("Prayer", 72), CRAFTING("Crafting", 30), FIREMAKING("Firemaking", 40),
    MAGIC("Magic", 65), FLETCHING("Fletching", 25), WOODCUTTING("Woodcutting", 25),
    RUNECRAFT("Runecraft", 10), SLAYER("Slayer", 30), FARMING("Farming", 26),
    CONSTRUCTION("Construction", 90), HUNTER("Hunter", 32);

    val article: String get() = if (title[0].lowercaseChar() in "aeiou") "an" else "a"
}

/** The real RuneScape experience curve: 83 xp for level 2, 13,034,431 for level 99. */
object Xp {
    const val MAX = 200_000_000L
    private val table = LongArray(100)

    init {
        var points = 0.0
        for (lvl in 1 until 99) {
            points += floor(lvl + 300.0 * 2.0.pow(lvl / 7.0))
            table[lvl + 1] = floor(points / 4).toLong()
        }
    }

    fun forLevel(level: Int): Long = table[level.coerceIn(1, 99)]

    fun level(xp: Long): Int {
        var l = 1
        while (l < 99 && xp >= table[l + 1]) l++
        return l
    }

    fun combatLevel(lv: (Skill) -> Int): Int {
        val base = 0.25 * (lv(Skill.DEFENCE) + lv(Skill.HITPOINTS) + floor(lv(Skill.PRAYER) / 2.0))
        val melee = 0.325 * (lv(Skill.ATTACK) + lv(Skill.STRENGTH))
        val range = 0.325 * floor(lv(Skill.RANGED) * 1.5)
        val mage = 0.325 * floor(lv(Skill.MAGIC) * 1.5)
        return floor(base + maxOf(melee, range, mage)).toInt()
    }
}

/** Decides which skill an app trains, from its package name, label and Play Store category. */
object SkillMap {
    private class Rule(val skill: Skill, vararg val words: String)

    private val rules = listOf(
        Rule(Skill.PRAYER, "youtube.music"),
        Rule(Skill.FIREMAKING, "musically"),
        Rule(Skill.SLAYER, "jagex", "runescape", "osrs", "runelite"),
        Rule(Skill.THIEVING, "reddit", "facebook", "instagram", "twitter", "threads", "snapchat", "linkedin",
            "bluesky", "mastodon", "social", "tumblr", "barcelona"),
        Rule(Skill.MAGIC, "openai", "chatgpt", "claude", "anthropic", "gemini", "copilot", "perplexity"),
        Rule(Skill.DEFENCE, "dialer", "phone", "contacts", "incallui"),
        Rule(Skill.RANGED, "messag", "sms", "mms", "whatsapp", "telegram", "signal", "discord", "chat", "viber"),
        Rule(Skill.FLETCHING, "mail", "outlook", "proton", "thunderbird"),
        Rule(Skill.HUNTER, "camera", "scanner", "lens"),
        Rule(Skill.CRAFTING, "photo", "gallery", "image", "picture", "pinterest", "canva", "lightroom", "snapseed",
            "draw", "paint", "sketch"),
        Rule(Skill.COOKING, "food", "eats", "doordash", "grubhub", "recipe", "restaurant", "pizza", "deliveroo",
            "starbucks", "mcdonald", "kitchen", "yelp"),
        Rule(Skill.AGILITY, "maps", "map", "navi", "waze", "uber", "lyft", "transit", "citymapper"),
        Rule(Skill.HERBLORE, "health", "medic", "pharm", "doctor", "headspace", "calm", "meditat", "sleep"),
        Rule(Skill.STRENGTH, "fit", "strava", "workout", "gym", "steps", "peloton"),
        Rule(Skill.MINING, "bank", "pay", "wallet", "finance", "money", "coin", "crypto", "venmo", "cash",
            "invest", "robinhood", "revolut", "monzo", "trading", "stock"),
        Rule(Skill.FISHING, "shop", "amazon", "ebay", "store", "market", "etsy", "temu", "aliexpress", "shein",
            "walmart", "ikea", "vending"),
        Rule(Skill.FARMING, "weather", "calendar", "clock", "alarm", "garden", "plant"),
        Rule(Skill.PRAYER, "music", "spotify", "podcast", "audio", "radio", "sound", "deezer", "tidal",
            "pandora", "audible", "shazam"),
        Rule(Skill.FIREMAKING, "youtube", "netflix", "twitch", "tiktok", "video", "hulu", "disney", "primevideo",
            "hbo", "vlc", "plex", "crunchyroll"),
        Rule(Skill.RUNECRAFT, "browser", "chrome", "firefox", "opera", "brave", "duckduckgo", "internet",
            "vivaldi", "search", "googlequicksearchbox"),
        Rule(Skill.MAGIC, "calc", "assistant"),
        Rule(Skill.SMITHING, "setting", "manager", "tool", "vpn", "security", "auth", "password", "termux",
            "cleaner", "backup"),
        Rule(Skill.CONSTRUCTION, "note", "docs", "office", "sheet", "drive", "keep", "notion", "word", "excel",
            "slides", "files", "documents", "todo", "task", "trello", "slack", "teams", "zoom", "obsidian",
            "evernote", "dropbox"),
        Rule(Skill.SLAYER, "news", "flipboard", "feedly", "nytimes", "bbc", "cnn", "guardian"),
        Rule(Skill.ATTACK, "game", "supercell", "chess", "roblox", "minecraft", "mojang", "pokemon",
            "nintendo", "epicgames", "steam"),
    )

    private val fallback = listOf(
        Skill.WOODCUTTING, Skill.WOODCUTTING, Skill.FISHING, Skill.MINING, Skill.SMITHING, Skill.COOKING,
        Skill.FIREMAKING, Skill.CRAFTING, Skill.FARMING, Skill.HUNTER, Skill.AGILITY, Skill.CONSTRUCTION,
    )

    fun skillFor(packageName: String, label: String, category: Int): Skill {
        if (category == ApplicationInfo.CATEGORY_GAME) return Skill.ATTACK
        val hay = "${packageName.lowercase()} ${label.lowercase()}"
        for (rule in rules) if (rule.words.any { it in hay }) return rule.skill
        when (category) {
            ApplicationInfo.CATEGORY_AUDIO -> return Skill.PRAYER
            ApplicationInfo.CATEGORY_VIDEO -> return Skill.FIREMAKING
            ApplicationInfo.CATEGORY_IMAGE -> return Skill.CRAFTING
            ApplicationInfo.CATEGORY_SOCIAL -> return Skill.THIEVING
            ApplicationInfo.CATEGORY_NEWS -> return Skill.SLAYER
            ApplicationInfo.CATEGORY_MAPS -> return Skill.AGILITY
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return Skill.CONSTRUCTION
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> return Skill.DEFENCE
        }
        return fallback[Math.floorMod(packageName.hashCode(), fallback.size)]
    }
}
