package com.ramong.runelauncher.core

enum class Channel { GAME, PUBLIC, PRIVATE, CHANNEL, CLAN, TRADE }

/** A chat line. [text] may contain `<col=rrggbb>` tags. */
class ChatLine(val channel: Channel, val text: String)

/** What the chatbox shows instead of chat: level-ups, quest scrolls, yes/no questions. */
sealed class Dialogue {
    class LevelUp(val skill: Skill, val level: Int) : Dialogue()
    class Message(val title: String, val lines: List<String>) : Dialogue()
    class Options(val title: String, val options: List<Pair<String, () -> Unit>>) : Dialogue()
}

/** One row of a right-click ("Choose Option") menu: "Open" + " Chrome" in item orange. */
class MenuOption(
    val verb: String,
    val target: String? = null,
    val targetColor: Int = 0xFFFF9040.toInt(),
    val action: () -> Unit = {},
)

class QuestDef(val id: String, val name: String, val hint: String)

/** RuneScape-style chat effects: "red:wave:Selling lobbies". */
object ChatEffects {
    val colors = listOf("yellow", "red", "green", "cyan", "purple", "white", "flash1", "flash2", "flash3", "glow1", "glow2", "glow3")
    val effects = listOf("wave", "wave2", "shake", "scroll", "slide")

    class Parsed(val color: String, val effect: String?, val body: String)

    fun parse(s: String): Parsed {
        var rest = s
        var color = "yellow"
        var effect: String? = null
        repeat(2) {
            val idx = rest.indexOf(':')
            if (idx <= 0) return@repeat
            val p = rest.substring(0, idx).lowercase()
            if (p in colors) {
                color = p
                rest = rest.substring(idx + 1)
            } else if (p in effects) {
                effect = p
                rest = rest.substring(idx + 1)
            }
        }
        return Parsed(color, effect, rest)
    }
}

object Content {
    val builtinQuests = listOf(
        QuestDef("tutorial", "Tutorial Island", "Set RuneLauncher as your default home app."),
        QuestDef("withdraw", "Withdrawal Symptoms", "Withdraw an item from the Bank of Gielinor."),
        QuestDef("examine", "Curiosity Killed the Cat", "Examine any item. Look closer."),
        QuestDef("wield", "Fashionscape", "Wield something from your inventory."),
        QuestDef("chat", "Yapper's Assistant", "Say something in public chat. Anything."),
        QuestDef("emote", "Emotional Support", "Perform an emote. Nobody is watching."),
        QuestDef("spell", "Spell Check", "Cast a spell from your spellbook."),
        QuestDef("grind", "The Grind", "Reach a total level of 100. Only 2,277 to go after that."),
        QuestDef("logout", "Dramatic Exit", "Use the logout button. You won't, though."),
    )

    val tracks = listOf(
        "Harmony", "Newbie Melody", "Autumn Voyage", "Sea Shanty 2", "Flute Salad", "Scape Main",
        "Adventure", "Medieval", "Garden", "Spooky", "Start", "Dream", "Fanfare", "Expanse", "Arabian",
        "Wilderness", "Yesteryear", "Horizon", "Book of Spells", "Dark", "Wonder", "Long Ago",
        "Greatness", "Vision", "Miles Away", "Still Night", "Gnome Village", "Attention", "Nightfall",
        "Lullaby", "Parade", "Barbarianism", "Unknown Land", "Alone", "Army of Darkness", "Background",
        "Crystal Sword", "Inspiration", "Iron", "Lightness", "Mellow", "Moody", "Neverland", "Overture",
        "Principality", "Splendour", "Tree Spirits", "Trinity", "Venture", "Wander",
    )

    val examines = listOf(
        "It's a %s.",
        "A %s. Seems useful.",
        "%s. It vibrates occasionally.",
        "A trusty %s.",
        "I could open this %s... or I could keep scrolling.",
        "%s. Probably wants notification permission.",
        "Looks like a %s to me.",
        "A %s. Updated recently, no doubt.",
        "It's some sort of %s.",
        "%s. Not tradeable on the Grand Exchange.",
        "A %s. I've seen better. I've also seen worse.",
        "%s. It hums with a faint, battery-draining energy.",
    )

    val playerQuotes = listOf(
        "Buying gf",
        "cyan:scroll:Selling lobbies 250 ea",
        "flash1:wave:Free armour trimming!",
        "Wc lvl?",
        "red:Doubling money",
        "Can I have some free stuff plz",
        "Nice.",
        "I'm not lost. I'm exploring.",
        "Just one more app.",
        "Lag.",
        "green:wave:Gz!",
        "Bank? Bank?",
        "glow1:wave2:Party at my house!",
        "yellow:shake:Why is my battery at 3%",
        "I have 99 problems and Runecraft is all of them.",
    )

    const val HELP = "Commands: ::bank, ::wiki <q>, ::search <q>, ::name <name>, ::emote <name>, ::xp, ::reset, " +
        "::home, ::wallpaper, ::settings, ::world, ::pet. Start with / to talk instead of search."
}
