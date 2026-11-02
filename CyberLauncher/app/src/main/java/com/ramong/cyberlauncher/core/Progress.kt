package com.ramong.cyberlauncher.core

import android.content.pm.ApplicationInfo
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The five attributes, in the order they sit around the character pentagon (clockwise from
 * the top). Each one has the skill tree it feeds and a note on which apps train it.
 */
enum class Attr(val title: String, val short: String, val skill: String, val trains: String) {
    BODY("Body", "BODY", "Solo", "fitness, health, food, maps, travel"),
    REFLEXES("Reflexes", "REF", "Shinobi", "games, video, music, streaming"),
    TECH("Technical Ability", "TECH", "Engineer", "tools, settings, camera, files"),
    INT("Intelligence", "INT", "Netrunner", "browsers, AI, news, docs, email"),
    COOL("Cool", "COOL", "Headhunter", "social, messaging, calls, shopping, money"),
}

/** Decides which attribute an app trains, from its package name, label and Play Store category. */
object AttrMap {
    private class Rule(val attr: Attr, vararg val words: String)

    // First match wins, so the specific rules (AI, work apps) come before the broad ones.
    private val rules = listOf(
        Rule(Attr.INT, "openai", "chatgpt", "claude", "anthropic", "gemini", "copilot", "perplexity", "bard"),
        Rule(Attr.INT, "mail", "outlook", "proton", "thunderbird", "docs", "office", "sheet", "notion", "slack",
            "teams", "zoom", "obsidian", "evernote", "trello", "todo", "onenote"),
        Rule(Attr.REFLEXES, "youtube", "netflix", "twitch", "tiktok", "musically", "spotify", "music", "video",
            "hulu", "disney", "primevideo", "hbo", "vlc", "plex", "crunchyroll", "podcast", "audible", "deezer",
            "tidal", "pandora", "soundcloud", "shazam", "radio", "player", "steam", "epicgames", "roblox",
            "minecraft", "mojang", "supercell", "nintendo", "pokemon", "chess", "game", "xbox", "playstation"),
        Rule(Attr.COOL, "reddit", "facebook", "instagram", "twitter", "threads", "snapchat", "linkedin", "bluesky",
            "mastodon", "social", "tumblr", "barcelona", "whatsapp", "telegram", "signal", "discord", "messag",
            "sms", "mms", "chat", "viber", "wechat", "line.android", "dialer", "phone", "contacts", "incallui",
            "tinder", "bumble", "hinge", "dating", "pinterest", "bank", "pay", "wallet", "finance", "money",
            "coin", "crypto", "venmo", "cash", "invest", "robinhood", "revolut", "monzo", "trading", "shop",
            "amazon", "ebay", "store", "market", "etsy", "temu", "aliexpress", "shein", "walmart", "vinted",
            "gallery", "photos"),
        Rule(Attr.INT, "browser", "chrome", "firefox", "opera", "brave", "duckduckgo", "internet", "vivaldi",
            "search", "googlequicksearchbox", "assistant", "news", "flipboard", "feedly", "nytimes", "bbc",
            "cnn", "guardian", "kindle", "reader", "book", "wiki", "duolingo", "learn", "edu", "note", "drive",
            "keep", "word", "excel", "slides", "task", "calendar", "translate"),
        Rule(Attr.BODY, "fit", "strava", "workout", "gym", "steps", "peloton", "health", "medic", "pharm",
            "doctor", "headspace", "calm", "meditat", "sleep", "food", "eats", "doordash", "grubhub", "recipe",
            "restaurant", "pizza", "deliveroo", "starbucks", "mcdonald", "kitchen", "yelp", "maps", "navi",
            "waze", "uber", "lyft", "transit", "citymapper", "airbnb", "booking", "travel", "flight", "weather",
            "run", "bike", "garden"),
        Rule(Attr.TECH, "camera", "scanner", "lens", "setting", "manager", "tool", "vpn", "security", "auth",
            "password", "termux", "cleaner", "backup", "files", "documents", "calc", "clock", "alarm", "flash",
            "torch", "remote", "home", "nest", "smartthings", "launcher", "terminal", "adb", "github", "code",
            "editor", "lightroom", "snapseed", "canva", "draw", "paint", "sketch", "photo"),
    )

    private val fallback = listOf(Attr.TECH, Attr.TECH, Attr.INT, Attr.COOL, Attr.BODY, Attr.REFLEXES)

    fun attrFor(packageName: String, label: String, category: Int): Attr {
        if (category == ApplicationInfo.CATEGORY_GAME) return Attr.REFLEXES
        val hay = "${packageName.lowercase()} ${label.lowercase()}"
        for (rule in rules) if (rule.words.any { it in hay }) return rule.attr
        when (category) {
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> return Attr.REFLEXES
            ApplicationInfo.CATEGORY_IMAGE -> return Attr.TECH
            ApplicationInfo.CATEGORY_SOCIAL -> return Attr.COOL
            ApplicationInfo.CATEGORY_NEWS, ApplicationInfo.CATEGORY_PRODUCTIVITY -> return Attr.INT
            ApplicationInfo.CATEGORY_MAPS -> return Attr.BODY
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> return Attr.TECH
        }
        return fallback[Math.floorMod(packageName.hashCode(), fallback.size)]
    }
}

/**
 * Item rarity, earned by use: an app you open every day ends up Legendary. The single most-used
 * app becomes Iconic once it has [ICONIC_AT] launches.
 */
enum class Rarity(val title: String, val color: Int, val minLaunches: Int) {
    COMMON("Common", 0xFFA3A9AF.toInt(), 0),
    UNCOMMON("Uncommon", 0xFF4FE07A.toInt(), 5),
    RARE("Rare", 0xFF3FA2FF.toInt(), 25),
    EPIC("Epic", 0xFFB86BFF.toInt(), 75),
    LEGENDARY("Legendary", 0xFFFFA43A.toInt(), 200),
    ICONIC("Iconic", 0xFFFCEE0A.toInt(), Int.MAX_VALUE);

    companion object {
        const val ICONIC_AT = 400

        fun of(launches: Int, isTop: Boolean = false): Rarity = when {
            isTop && launches >= ICONIC_AT -> ICONIC
            launches >= LEGENDARY.minLaunches -> LEGENDARY
            launches >= EPIC.minLaunches -> EPIC
            launches >= RARE.minLaunches -> RARE
            launches >= UNCOMMON.minLaunches -> UNCOMMON
            else -> COMMON
        }
    }
}

/**
 * Experience curves. Level 60 is roughly two years of normal phone use, attributes cap at 20
 * like the game, and Street Cred tops out at 50.
 */
object Curves {
    const val MAX_LEVEL = 60
    const val ATTR_MIN = 3
    const val ATTR_MAX = 20
    const val MAX_CRED = 50

    private val levelTable = LongArray(MAX_LEVEL + 2) { l -> if (l <= 1) 0L else (250.0 * (l - 1.0).pow(2.2)).roundToLong() }
    private val attrTable = LongArray(ATTR_MAX + 2) { l -> if (l <= ATTR_MIN) 0L else (400.0 * (l - ATTR_MIN.toDouble()).pow(2.3)).roundToLong() }
    private val credTable = LongArray(MAX_CRED + 2) { l -> if (l <= 1) 0L else (40.0 * (l - 1.0).pow(2.1)).roundToLong() }

    fun xpForLevel(l: Int) = levelTable[l.coerceIn(1, MAX_LEVEL)]
    fun xpForAttr(l: Int) = attrTable[l.coerceIn(ATTR_MIN, ATTR_MAX)]
    fun credForLevel(l: Int) = credTable[l.coerceIn(1, MAX_CRED)]

    fun level(xp: Long) = find(levelTable, xp, 1, MAX_LEVEL)
    fun attrLevel(xp: Long) = find(attrTable, xp, ATTR_MIN, ATTR_MAX)
    fun credLevel(xp: Long) = find(credTable, xp, 1, MAX_CRED)

    private fun find(t: LongArray, xp: Long, min: Int, max: Int): Int {
        var l = min
        while (l < max && xp >= t[l + 1]) l++
        return l
    }

    /** 0..1 progress from the current level to the next. Full at the cap. */
    fun progress(xp: Long, level: Int, table: (Int) -> Long, max: Int): Float {
        if (level >= max) return 1f
        val a = table(level)
        val b = table(level + 1)
        return if (b > a) ((xp - a).toFloat() / (b - a)).coerceIn(0f, 1f) else 1f
    }
}
