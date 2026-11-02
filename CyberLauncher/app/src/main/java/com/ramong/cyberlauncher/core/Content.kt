package com.ramong.cyberlauncher.core

/** Who a feed line is from. Drawn as a coloured tag in front of the text. */
enum class Tag(val label: String) {
    SYS("SYS"), XP("XP"), EDDIES("€$"), JOB("JOB"), MSG("MSG"), NCPD("NCPD"), YOU(">"), ERR("ERR"), SCAN("SCAN")
}

/** One line in the net feed. [text] may contain `<col=..>` tags (see Cy.rich). */
class FeedLine(val tag: Tag, val text: String, val time: Long)

/** A row of the long-press menu. [key] is the keyboard hint drawn in front ("F", "Q"...). */
class MenuOption(val verb: String, val key: String = "", val danger: Boolean = false, val action: () -> Unit = {})

class ItemMenu(
    val title: String,
    val subtitle: String,
    val titleColor: Int,
    val options: List<MenuOption>,
    val x: Float,
    val y: Float,
)

/** A dialogue choice, drawn like the game's conversation options. */
class ChoiceOption(val text: String, val primary: Boolean = false, val danger: Boolean = false, val action: () -> Unit = {})

class Choice(val speaker: String, val title: String, val options: List<ChoiceOption>, val cancellable: Boolean = true)

/** The big notifications that slide across the city: level ups, finished jobs, upgrades. */
class Banner(val kind: Kind, val title: String, val sub: String = "", val extra: String = "") {
    enum class Kind { LEVEL, ATTR, CRED, JOB, ITEM, CALL, VENDOR }
}

/**
 * A colour scheme. The three lifepaths are free; the rest are sold by the vendor in Settings.
 * [haze] tints the sky glow over the city.
 */
class Palette(
    val id: String,
    val name: String,
    val price: Int,
    val tagline: String,
    val primary: Int,
    val accent: Int,
    val hi: Int,
    val bg: Int,
    val haze: Int,
)

enum class Lifepath(val title: String, val palette: String, val tagline: String, val story: String) {
    NOMAD(
        "Nomad", "nomad", "Family first. Roads second. Cities a distant third.",
        "Raised by a clan in the badlands. Swapped the open road for the city lights and still trusts a car more than most people.",
    ),
    STREET_KID(
        "Street Kid", "street", "Knows every alley. Owes money in most of them.",
        "Grew up on Night City's streets. Knows the fixers, the shortcuts and exactly which rules are optional.",
    ),
    CORPO(
        "Corpo", "corpo", "Signed the contract. Read the fine print later.",
        "Climbed the tower one backstab at a time. Knows what everything costs, including you.",
    ),
}

class QuickhackDef(val id: String, val name: String, val does: String, val ram: Int)

class Station(val freq: String, val name: String, val genre: String)

class JobDef(val id: String, val title: String, val hint: String)

/** Tracked to-do. Main jobs are built in and teach the launcher; gigs are the user's own. */
class Job(val id: String, var title: String, var note: String, var done: Boolean, val main: Boolean, val created: Long) {
    companion object {
        const val MAIN_XP = 500
        const val MAIN_EDDIES = 1000
        const val MAIN_CRED = 150
        const val GIG_XP = 150
        const val GIG_CRED = 100
    }
}

object Content {
    val palettes = listOf(
        Palette("street", "Street Kid", 0, "Coral and cyan. The classic.",
            0xFFFF5D52.toInt(), 0xFF5EF6FF.toInt(), 0xFFFCEE0A.toInt(), 0xFF0B090D.toInt(), 0xFF7A1A2E.toInt()),
        Palette("corpo", "Corpo", 0, "Ice blue and quiet menace.",
            0xFF7CC4FF.toInt(), 0xFFE9F6FF.toInt(), 0xFFFF3B5C.toInt(), 0xFF070A10.toInt(), 0xFF16345E.toInt()),
        Palette("nomad", "Nomad", 0, "Desert amber and old chrome.",
            0xFFFFAE45.toInt(), 0xFF41E2C4.toInt(), 0xFFFCEE0A.toInt(), 0xFF0C0906.toInt(), 0xFF6A3612.toInt()),
        Palette("samurai", "Samurai", 2_500, "Yellow on black. Loud on purpose.",
            0xFFFCEE0A.toInt(), 0xFFFF2A3D.toInt(), 0xFF5EF6FF.toInt(), 0xFF0A0A04.toInt(), 0xFF5A4A08.toInt()),
        Palette("netrunner", "Netrunner", 5_000, "Phosphor green, straight off the deck.",
            0xFF3DFF9C.toInt(), 0xFF5EF6FF.toInt(), 0xFFE8FF4A.toInt(), 0xFF040B08.toInt(), 0xFF0F4A30.toInt()),
        Palette("neon", "Neon", 10_000, "Magenta and cyan, all night long.",
            0xFFFF3FD2.toInt(), 0xFF4DF3FF.toInt(), 0xFFFCEE0A.toInt(), 0xFF0B0510.toInt(), 0xFF5A1660.toInt()),
        Palette("chrome", "Chrome", 20_000, "Brushed silver. Expensive taste.",
            0xFFD9E1EA.toInt(), 0xFF5EF6FF.toInt(), 0xFFFCEE0A.toInt(), 0xFF08090B.toInt(), 0xFF2A3040.toInt()),
        Palette("redshift", "Redshift", 40_000, "Arterial red. For the dramatic.",
            0xFFFF2B2B.toInt(), 0xFFFF9A3C.toInt(), 0xFFFFFFFF.toInt(), 0xFF0E0405.toInt(), 0xFF6A0A10.toInt()),
    )

    fun palette(id: String?): Palette = palettes.firstOrNull { it.id == id } ?: palettes[0]

    val quickhacks = listOf(
        QuickhackDef("ping", "Ping", "Wi-Fi and mobile data", 1),
        QuickhackDef("optics", "Reboot Optics", "Flashlight", 2),
        QuickhackDef("sonic", "Sonic Shock", "Volume", 2),
        QuickhackDef("wipe", "Memory Wipe", "Do Not Disturb", 3),
        QuickhackDef("cripple", "Cripple Movement", "Location", 3),
        QuickhackDef("malfunction", "Cyberware Malfunction", "Bluetooth", 3),
        QuickhackDef("overheat", "Overheat", "Battery saver", 4),
        QuickhackDef("backup", "Request Backup", "Hotspot and tethering", 2),
        QuickhackDef("reset", "System Reset", "Airplane mode", 6),
        QuickhackDef("breach", "Breach Protocol", "Everything else (Android settings)", 0),
    )

    val stations = listOf(
        Station("87.3", "Static Bloom", "Darkwave for people who own one lamp"),
        Station("89.1", "Neon Prayer", "Synth hymns, broadcast from a church that sells ads"),
        Station("91.5", "Chrome & Gasoline", "Industrial rock, played at engine volume"),
        Station("93.9", "Dead Signal", "Mostly noise. Loyal listeners swear it's music"),
        Station("95.3", "Kabuki Nights", "City pop for the long ride home"),
        Station("97.7", "Radio Flatline", "Hard techno. Pulse optional"),
        Station("99.1", "Synth Cemetery", "Retro synth. Everything was better in 2020"),
        Station("101.9", "Midnight Arcade", "Chiptune, pumped straight into your optics"),
        Station("103.5", "Offworld Lounge", "Lounge jazz for orbital commuters"),
        Station("105.1", "Badlands Dust", "Nomad folk, with tyre noise"),
        Station("107.9", "Pirate Net", "Unlicensed. Untraceable. Mostly ads anyway"),
        Station("109.3", "No Future FM", "Punk. Obviously"),
    )

    val districts = listOf(
        "WATSON" to "KABUKI", "WATSON" to "LITTLE CHINA", "WATSON" to "NORTHSIDE",
        "WESTBROOK" to "JAPANTOWN", "WESTBROOK" to "CHARTER HILL", "WESTBROOK" to "NORTH OAK",
        "CITY CENTER" to "DOWNTOWN", "CITY CENTER" to "CORPO PLAZA",
        "HEYWOOD" to "THE GLEN", "HEYWOOD" to "WELLSPRINGS", "HEYWOOD" to "VISTA DEL REY",
        "SANTO DOMINGO" to "ARROYO", "SANTO DOMINGO" to "RANCHO CORONADO",
        "PACIFICA" to "COASTVIEW", "PACIFICA" to "WEST WIND", "DOGTOWN" to "THE BAZAAR",
        "BADLANDS" to "ROCKY RIDGE", "BADLANDS" to "RED PEAKS",
    )

    val mainJobs = listOf(
        JobDef("rescue", "The Rescue", "Set CyberLauncher as your default home app."),
        JobDef("pickup", "The Pickup", "Add an item from your stash to your inventory."),
        JobDef("scan", "Eyes Wide Open", "Scan any item. Hold it and pick SCAN."),
        JobDef("chrome", "Chromed Up", "Install cyberware: put an app in a cyberware slot."),
        JobDef("jackin", "Jacked In", "Upload any quickhack."),
        JobDef("gig", "Gig Economy", "Add a gig to your journal, then finish it."),
        JobDef("radio", "On Air", "Tune into a radio station."),
        JobDef("vendor", "Retail Therapy", "Buy a new look from the vendor in Settings."),
        JobDef("legend", "Living Legend", "Make any item Legendary. (Use it 200 times.)"),
        JobDef("quit", "Quit Game?", "Find the QUIT button. You won't press it."),
    )

    val bootLines = listOf(
        "OPTICS FIRMWARE 77.2 .............. OK",
        "BIOMONITOR LINK ................... OK",
        "NEURAL PORT HANDSHAKE ............. OK",
        "CYBERDECK RAM ..................... %RAM%",
        "STASH INDEX ....................... %APPS% ITEMS",
        "NCPD SCANNER FREQ ................. JAMMED",
        "ICE WARNINGS ...................... IGNORED",
        "WELCOME BACK, %NAME%.",
    )

    val intel = listOf(
        "%s. Probably phones home more than you do.",
        "%s. Firmware older than your jacket.",
        "%s. Runs fine. Suspiciously fine.",
        "%s. Half the city has it installed. Nobody knows why.",
        "%s. Collects data like a fixer collects favours.",
        "%s. Would sell you out for a five-star review.",
        "%s. Comes with a free subscription you'll forget to cancel.",
        "%s. Encrypted, allegedly.",
        "%s. Clean code, dirty business model.",
        "%s. Rated 4.2 stars by people who never read the terms.",
        "%s. It knows what you did last night.",
        "%s. Street value: questionable.",
    )

    val threatLines = listOf(
        "Mostly harmless. Mostly.",
        "Keep an eye on it.",
        "Knows more about you than your ripperdoc.",
        "Corpo-grade surveillance. Nova.",
        "Absolute data vampire. Respect.",
    )

    val catLines = listOf(
        "The cat ignores you. Classic.",
        "Mrrp.",
        "The cat blinks slowly. You have been approved.",
        "The cat knocks a can off the roof. Somewhere below, someone swears.",
        "The cat demands tribute. You have none.",
    )

    val vTaps = listOf(
        "Preem.",
        "Nova.",
        "Just one more app, choom.",
        "Another day, another eddie.",
        "Don't make me flatline your screen time.",
        "Who needs sleep when you have notifications?",
        "Reality is overrated. Battery isn't.",
    )

    val callTips = listOf(
        "Choom, listen. Tap the prompt to search your stash. Enter opens the top hit.",
        "Hold an item for the menu. Hold and drag to move it around your inventory.",
        "Swipe up for the stash. Swipe down for notifications. Swipe sideways to flip tabs.",
        "Every app you open pays out XP and eddies. Use one enough and it goes Legendary.",
    )

    const val HELP = "Commands: /help /stash /scan <app> /net <query> /name <handle> /theme <name> /stats /heat " +
        "/eddies /travel /rain /glitch /breach /home /wallpaper /settings /reset. Anything else searches your stash."
}
