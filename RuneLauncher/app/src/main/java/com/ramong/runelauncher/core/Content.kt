package com.ramong.runelauncher.core

/** Who a toast is from. Drawn as a small coloured tag in front of the text. */
enum class Tag(val label: String) { GAME("Game"), NEWS("News"), XP("XP"), QUEST("Quest"), ERR("Oh dear"), YOU("You") }

/** One message. [text] may contain `<col=..>` tags (see R3.rich). */
class FeedLine(val tag: Tag, val text: String, val time: Long)

/** A row of the long-press menu. */
class MenuOption(val verb: String, val danger: Boolean = false, val action: () -> Unit = {})

class ItemMenu(
    val title: String,
    val subtitle: String,
    val titleColor: Int,
    val options: List<MenuOption>,
    val x: Float,
    val y: Float,
)

/** A dialogue option, numbered like the game's chat options. */
class ChoiceOption(val text: String, val primary: Boolean = false, val danger: Boolean = false, val action: () -> Unit = {})

class Choice(val speaker: String, val title: String, val options: List<ChoiceOption>, val cancellable: Boolean = true)

/** The big notices across the top: level ups, quests, achievements. */
class Banner(val kind: Kind, val title: String, val sub: String = "", val extra: String = "", val skill: Skill? = null) {
    enum class Kind { LEVEL, QUEST, ACHIEVEMENT, INFO }
}

/**
 * An interface theme. [frame] is the metal of the borders, [hi] the title text, [accent] the
 * highlight for buttons and bars, [bg] the panel glass.
 */
class Palette(val id: String, val name: String, val tagline: String, val frame: Int, val hi: Int, val accent: Int, val bg: Int)

class AchievementDef(val id: String, val title: String, val hint: String)

class PrayerDef(val id: String, val label: String, val prayer: String)

class Place(val name: String, val region: String)

object Content {
    val palettes = listOf(
        Palette("gold", "Gielinor Gold", "The classic. Bronze, gold and a lot of dark glass.",
            0xFFB08A48.toInt(), 0xFFF3D48E.toInt(), 0xFF5CC3E8.toInt(), 0xFF0F171D.toInt()),
        Palette("necro", "Necromancy", "Purple, green and a faint smell of graveyard.",
            0xFF8A68B8.toInt(), 0xFFD9C6F7.toInt(), 0xFF78E5A2.toInt(), 0xFF120E18.toInt()),
        Palette("steel", "Ancient Steel", "Cold metal for people who don't like gold.",
            0xFF8C9DAE.toInt(), 0xFFE3EBF2.toInt(), 0xFFE8B55A.toInt(), 0xFF0E1419.toInt()),
        Palette("dragon", "Dragonkin", "Embers and scales. Mind the fire breath.",
            0xFFB85A3E.toInt(), 0xFFF6C29A.toInt(), 0xFFF2C14E.toInt(), 0xFF180E0C.toInt()),
        Palette("menaphos", "Menaphos", "Sandstone, turquoise and a suspicious number of scarabs.",
            0xFFC9A55C.toInt(), 0xFFFFE6A8.toInt(), 0xFF3FC2AF.toInt(), 0xFF17120C.toInt()),
    )

    fun palette(id: String) = palettes.firstOrNull { it.id == id } ?: palettes[0]

    val achievements = listOf(
        AchievementDef("home", "Lumbridge Home", "Set RuneLauncher as your home app."),
        AchievementDef("bank", "Withdrawal Slip", "Add an app to your home screen from the bank."),
        AchievementDef("folder", "Rune Pouch", "Make a folder by dropping one app onto another."),
        AchievementDef("examine", "Inspector", "Examine an app."),
        AchievementDef("log", "Wise Old Man", "Open your Adventurer's Log."),
        AchievementDef("quest", "Quest Cape (Pending)", "Complete a quest from your journal."),
        AchievementDef("prayer", "Devout", "Use a quick setting from the prayer book."),
        AchievementDef("theme", "Interior Decorator", "Change your interface theme."),
        AchievementDef("total100", "Getting Somewhere", "Reach a total level of 100."),
        AchievementDef("level50", "Halfway to 92", "Reach level 50 in any skill."),
    )

    /** Quick settings, each wearing the name of a prayer. */
    val prayers = listOf(
        PrayerDef("internet", "Internet", "Mystic Lore"),
        PrayerDef("torch", "Flashlight", "Protect from Darkness"),
        PrayerDef("bluetooth", "Bluetooth", "Deflect Ranged"),
        PrayerDef("dnd", "Do Not Disturb", "Protect from Melee"),
        PrayerDef("volume", "Volume", "Rapid Restore"),
        PrayerDef("location", "Location", "Eagle Eye"),
        PrayerDef("saver", "Battery Saver", "Rapid Heal"),
        PrayerDef("hotspot", "Hotspot", "Redemption"),
        PrayerDef("airplane", "Airplane", "Retribution"),
        PrayerDef("settings", "Settings", "Piety"),
    )

    val tracks = listOf(
        "Harmony", "Newbie Melody", "Autumn Voyage", "Sea Shanty 2", "Flute Salad", "Scape Main",
        "Adventure", "Medieval", "Garden", "Spooky", "Dream", "Fanfare", "Expanse", "Arabian",
        "Wilderness", "Yesteryear", "Horizon", "Book of Spells", "Wonder", "Long Ago",
        "Greatness", "Vision", "Miles Away", "Still Night", "Gnome Village", "Nightfall",
        "Lullaby", "Parade", "Barbarianism", "Unknown Land", "Alone", "Background", "Crystal Sword",
        "Inspiration", "Lightness", "Mellow", "Moody", "Overture", "Splendour", "Trinity", "Wander",
    )

    val places = listOf(
        Place("Lumbridge", "Misthalin"), Place("Varrock", "Misthalin"), Place("Draynor Village", "Misthalin"),
        Place("Falador", "Asgarnia"), Place("Burthorpe", "Asgarnia"), Place("Taverley", "Asgarnia"),
        Place("Port Sarim", "Asgarnia"), Place("Edgeville", "Misthalin"), Place("Al Kharid", "Kharidian Desert"),
        Place("Menaphos", "Kharidian Desert"), Place("Seers' Village", "Kandarin"), Place("Catherby", "Kandarin"),
        Place("Ardougne", "Kandarin"), Place("Prifddinas", "Tirannwn"), Place("Canifis", "Morytania"),
        Place("Fort Forinthry", "Wilderness border"), Place("Anachronia", "Far to the south"),
    )

    val examines = listOf(
        "It's a %s.",
        "A %s. Seems useful.",
        "%s. It vibrates occasionally.",
        "A trusty %s.",
        "I could open this %s... or I could keep scrolling.",
        "%s. Probably wants notification permission.",
        "A %s. Updated recently, no doubt.",
        "%s. Not tradeable on the Grand Exchange.",
        "A %s. I've seen better. I've also seen worse.",
        "%s. It hums with a faint, battery-draining energy.",
        "A %s. Members only, probably.",
    )

    val folderNames = mapOf(
        Skill.THIEVING to "Social", Skill.RANGED to "Messages", Skill.FIREMAKING to "Watch",
        Skill.PRAYER to "Music", Skill.ATTACK to "Games", Skill.MINING to "Money", Skill.FISHING to "Shopping",
        Skill.CONSTRUCTION to "Work", Skill.AGILITY to "Travel", Skill.COOKING to "Food",
        Skill.INVENTION to "Tools", Skill.STRENGTH to "Fitness", Skill.HERBLORE to "Health",
        Skill.MAGIC to "Magic", Skill.DIVINATION to "Plans", Skill.ARCHAEOLOGY to "Photos",
        Skill.RUNECRAFTING to "Web", Skill.SLAYER to "News", Skill.SMITHING to "Security",
    )

    const val HELP = "Type in the search: ::help, ::stats, ::name <name>, ::theme <name>, ::hop, ::wiki <q>, " +
        "::ge <item>, ::settings, ::log, ::reset. Anything else searches your apps, then the web."

    const val FIRST_HINT = "Welcome to Gielinor. Swipe up for the bank, swipe right for your Adventurer's Log, " +
        "and drop one app onto another to make a folder."
}
