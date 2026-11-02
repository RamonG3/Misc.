package com.ramong.runelauncher.core

import android.content.pm.ApplicationInfo
import kotlin.math.floor
import kotlin.math.pow

/**
 * The 29 RuneScape 3 skills, in the in-game skills interface order (three per row).
 * [xpPerLaunch] is what one app launch is worth; [maxLevel] is 99 or, for the elite skills, 120.
 */
enum class Skill(val title: String, val xpPerLaunch: Int, val maxLevel: Int = 99, val trains: String) {
    ATTACK("Attack", 45, trains = "games"),
    CONSTITUTION("Constitution", 15, trains = "everything, a little"),
    MINING("Mining", 40, trains = "banking, money and crypto"),
    STRENGTH("Strength", 45, trains = "fitness and workouts"),
    AGILITY("Agility", 35, trains = "maps, rides and travel"),
    SMITHING("Smithing", 40, trains = "passwords, VPNs and security"),
    DEFENCE("Defence", 45, trains = "calls and contacts"),
    HERBLORE("Herblore", 55, maxLevel = 120, trains = "health and meditation"),
    FISHING("Fishing", 50, trains = "shopping"),
    RANGED("Ranged", 45, trains = "messaging"),
    THIEVING("Thieving", 30, trains = "social media"),
    COOKING("Cooking", 35, trains = "food and delivery"),
    PRAYER("Prayer", 70, trains = "music, podcasts and audio"),
    CRAFTING("Crafting", 35, trains = "photo editing and drawing"),
    FIREMAKING("Firemaking", 45, trains = "video and streaming"),
    MAGIC("Magic", 65, trains = "AI assistants and calculators"),
    FLETCHING("Fletching", 30, trains = "email"),
    WOODCUTTING("Woodcutting", 30, trains = "anything unclassifiable"),
    RUNECRAFTING("Runecrafting", 12, trains = "browsers and search"),
    SLAYER("Slayer", 35, maxLevel = 120, trains = "news, and RuneScape itself"),
    FARMING("Farming", 30, maxLevel = 120, trains = "clocks, alarms and gardening"),
    CONSTRUCTION("Construction", 90, trains = "notes, docs and work apps"),
    HUNTER("Hunter", 35, trains = "the camera"),
    SUMMONING("Summoning", 40, trains = "smart home and pet apps"),
    DUNGEONEERING("Dungeoneering", 45, maxLevel = 120, trains = "file managers"),
    DIVINATION("Divination", 40, trains = "weather and calendars"),
    INVENTION("Invention", 60, maxLevel = 120, trains = "settings, tools and dev apps"),
    ARCHAEOLOGY("Archaeology", 45, maxLevel = 120, trains = "galleries and old photos"),
    NECROMANCY("Necromancy", 50, maxLevel = 120, trains = "backups, cloud storage and sync");

    val article: String get() = if (title[0].lowercaseChar() in "aeiou") "an" else "a"
}

/** The real RuneScape experience curve, extended to 120 for the elite skills. */
object Xp {
    const val MAX = 200_000_000L
    private val table = LongArray(121)

    init {
        var points = 0.0
        for (lvl in 1 until 120) {
            points += floor(lvl + 300.0 * 2.0.pow(lvl / 7.0))
            table[lvl + 1] = floor(points / 4).toLong()
        }
    }

    fun forLevel(level: Int): Long = table[level.coerceIn(1, 120)]

    fun level(xp: Long, max: Int = 99): Int {
        var l = 1
        while (l < max && xp >= table[l + 1]) l++
        return l
    }

    /** Fraction of the way from [level] to the next one. */
    fun progress(xp: Long, level: Int, max: Int): Float {
        if (level >= max) return 1f
        val a = forLevel(level)
        val b = forLevel(level + 1)
        return ((xp - a).toFloat() / (b - a)).coerceIn(0f, 1f)
    }

    /** RS3's combat level formula (Attack/Strength/Defence/Constitution/Ranged/Magic/Prayer/Summoning/Necromancy). */
    fun combatLevel(lv: (Skill) -> Int): Int {
        val melee = lv(Skill.ATTACK) + lv(Skill.STRENGTH)
        val best = maxOf(melee, 2 * lv(Skill.MAGIC), 2 * lv(Skill.RANGED), 2 * lv(Skill.NECROMANCY))
        val c = (13.0 / 10.0 * best + lv(Skill.DEFENCE) + lv(Skill.CONSTITUTION) + lv(Skill.PRAYER) / 2.0 + lv(Skill.SUMMONING) / 2.0) / 4.0
        return floor(c).toInt().coerceIn(3, 152)
    }
}

/** Decides which skill an app trains, from its package name, label and Play Store category. */
object SkillMap {
    private class Rule(val skill: Skill, vararg val words: String)

    // First match wins, so the specific rules come before the broad ones.
    private val rules = listOf(
        Rule(Skill.PRAYER, "youtube.music"),
        Rule(Skill.FIREMAKING, "musically"),
        Rule(Skill.SLAYER, "jagex", "runescape", "osrs", "runelite"),
        Rule(Skill.MAGIC, "openai", "chatgpt", "claude", "anthropic", "gemini", "copilot", "perplexity"),
        Rule(Skill.THIEVING, "reddit", "facebook", "instagram", "twitter", "threads", "snapchat", "linkedin",
            "bluesky", "mastodon", "social", "tumblr", "barcelona"),
        Rule(Skill.DEFENCE, "dialer", "phone", "contacts", "incallui"),
        Rule(Skill.RANGED, "messag", "sms", "mms", "whatsapp", "telegram", "signal", "discord", "chat", "viber"),
        Rule(Skill.FLETCHING, "mail", "outlook", "proton", "thunderbird"),
        Rule(Skill.HUNTER, "camera", "scanner", "lens"),
        Rule(Skill.ARCHAEOLOGY, "photos", "gallery", "album", "museum", "archive", "history"),
        Rule(Skill.CRAFTING, "image", "picture", "pinterest", "canva", "lightroom", "snapseed", "draw", "paint", "sketch"),
        Rule(Skill.COOKING, "food", "eats", "doordash", "grubhub", "recipe", "restaurant", "pizza", "deliveroo",
            "starbucks", "mcdonald", "kitchen", "yelp"),
        Rule(Skill.AGILITY, "maps", "map", "navi", "waze", "uber", "lyft", "transit", "citymapper", "airbnb", "flight"),
        Rule(Skill.HERBLORE, "health", "medic", "pharm", "doctor", "headspace", "calm", "meditat", "sleep"),
        Rule(Skill.STRENGTH, "fit", "strava", "workout", "gym", "steps", "peloton"),
        Rule(Skill.MINING, "bank", "pay", "wallet", "finance", "money", "coin", "crypto", "venmo", "cash",
            "invest", "robinhood", "revolut", "monzo", "trading", "stock"),
        Rule(Skill.FISHING, "shop", "amazon", "ebay", "store", "market", "etsy", "temu", "aliexpress", "shein",
            "walmart", "ikea", "vending"),
        Rule(Skill.DIVINATION, "weather", "calendar", "horoscope", "forecast"),
        Rule(Skill.FARMING, "clock", "alarm", "garden", "plant", "timer"),
        Rule(Skill.SUMMONING, "smarthome", "smartthings", "nest", "hue", "alexa", "google.android.apps.chromecast", "pet", "vet"),
        Rule(Skill.PRAYER, "music", "spotify", "podcast", "audio", "radio", "sound", "deezer", "tidal",
            "pandora", "audible", "shazam"),
        Rule(Skill.FIREMAKING, "youtube", "netflix", "twitch", "tiktok", "video", "hulu", "disney", "primevideo",
            "hbo", "vlc", "plex", "crunchyroll"),
        Rule(Skill.RUNECRAFTING, "browser", "chrome", "firefox", "opera", "brave", "duckduckgo", "internet",
            "vivaldi", "search", "googlequicksearchbox"),
        Rule(Skill.MAGIC, "calc", "assistant"),
        Rule(Skill.NECROMANCY, "backup", "restore", "recovery", "sync", "cloud", "dropbox", "onedrive", "apps.docs", "drive"),
        Rule(Skill.DUNGEONEERING, "files", "documents", "explorer", "filemanager"),
        Rule(Skill.SMITHING, "vpn", "security", "auth", "password", "bitwarden", "1password", "lastpass"),
        Rule(Skill.INVENTION, "setting", "manager", "tool", "termux", "cleaner", "github", "code", "terminal", "adb"),
        Rule(Skill.CONSTRUCTION, "note", "docs", "office", "sheet", "keep", "notion", "word", "excel",
            "slides", "todo", "task", "trello", "slack", "teams", "zoom", "obsidian", "evernote"),
        Rule(Skill.SLAYER, "news", "flipboard", "feedly", "nytimes", "bbc", "cnn", "guardian"),
        Rule(Skill.ATTACK, "game", "supercell", "chess", "roblox", "minecraft", "mojang", "pokemon",
            "nintendo", "epicgames", "steam"),
    )

    private val fallback = listOf(
        Skill.WOODCUTTING, Skill.WOODCUTTING, Skill.FISHING, Skill.MINING, Skill.SMITHING, Skill.COOKING,
        Skill.FIREMAKING, Skill.CRAFTING, Skill.FARMING, Skill.HUNTER, Skill.AGILITY, Skill.DIVINATION,
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
