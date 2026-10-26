package com.ramong.runelauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import com.ramong.runelauncher.core.Skill
import kotlin.math.floor
import kotlin.math.max

/** A tiny pixel-art image, drawn with nearest-neighbour scaling so it stays crunchy. */
class Sprite(val w: Int, val h: Int, pixels: IntArray) {
    val bitmap: Bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    private val dst = RectF()

    /** Draws centred on ([cx],[cy]) as large as fits in a [box]-sized square, preferring whole-number scales. */
    fun draw(c: Canvas, cx: Float, cy: Float, box: Float, paint: Paint = Sprites.paint) {
        val m = max(w, h).toFloat()
        var s = floor(box / m)
        if (s < 1f || s * m < box * 0.8f) s = box / m
        val hw = w * s / 2f
        val hh = h * s / 2f
        dst.set(cx - hw, cy - hh, cx + hw, cy + hh)
        c.drawBitmap(bitmap, null, dst, paint)
    }
}

/**
 * All of the launcher's hand-drawn art. Every sprite is an ASCII grid where each character
 * is a palette colour and '.' is transparent. No Jagex assets are used anywhere.
 */
object Sprites {
    val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    fun silhouette(color: Int) = Paint(paint).apply { colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    private val palette: Map<Char, Int> = mapOf(
        'K' to 0xFF000000, 'k' to 0xFF262626, 'G' to 0xFF4A4A4A, 'g' to 0xFF808080,
        'w' to 0xFFC0C0C0, 'W' to 0xFFFFFFFF, 'R' to 0xFFD02020, 'r' to 0xFF7A1212,
        'P' to 0xFFFF8A8A, 'O' to 0xFFFF8A1E, 'o' to 0xFFA04E14, 'Y' to 0xFFFFE13C,
        'y' to 0xFFB8901C, 'B' to 0xFF8A5A2E, 'b' to 0xFF553419, 'T' to 0xFFD8B27A,
        'N' to 0xFF3FB63F, 'n' to 0xFF1F6B1F, 'L' to 0xFF9BE85C, 'U' to 0xFF3264DC,
        'u' to 0xFF1B357F, 'C' to 0xFF6ED6F5, 'c' to 0xFF2E8FB0, 'M' to 0xFFA45AE0,
        'm' to 0xFF5B2C8F, 'S' to 0xFFE8B48A, 's' to 0xFFB47E58, 'X' to 0xFFC3CCD6,
        'x' to 0xFF77828E, 'E' to 0xFFE8E0C8, 'e' to 0xFFA89C7C,
    ).mapValues { it.value.toInt() }

    fun parse(art: String): Sprite {
        val rows = art.trimIndent().lines().filter { it.isNotBlank() }
        val w = rows.maxOf { it.length }
        val h = rows.size
        val pix = IntArray(w * h)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> pix[y * w + x] = palette[ch] ?: 0 } }
        return Sprite(w, h, pix)
    }

    // ------------------------------------------------------------------ skills

    val ATTACK by lazy {
        parse(
            """
            ..........KK
            .........KXK
            ........KXxK
            .......KXxK.
            ......KXxK..
            .KK..KXxK...
            .KyKKXxK....
            ..KyXxK.....
            ..KbyK......
            .KbKKyK.....
            KbK..KK.....
            KK..........
            """
        )
    }

    val STRENGTH by lazy {
        parse(
            """
            .......KKKK.
            ......KSSSSK
            ......KSSSsK
            ......KSSsK.
            .......KSSK.
            ..KKK..KSSK.
            .KSSSKKSSSK.
            KSSSSSSSSSK.
            KSSSSSSSSsK.
            KsSSSSSSssK.
            .KssssssssK.
            ..KKKKKKKK..
            """
        )
    }

    val DEFENCE by lazy {
        parse(
            """
            .KKKKKKKKK.
            KxXXXXXXXxK
            KxXUUUUUXxK
            KxXUCUUUXxK
            KxXUUUUUXxK
            KxXUUUUUXxK
            .KxXUUUXxK.
            .KxXUUUXxK.
            ..KxXUXxK..
            ...KxXxK...
            ....KxK....
            .....K.....
            """
        )
    }

    val RANGED by lazy {
        parse(
            """
            .KK.........
            KBbK........
            KBKwK.......
            KBK.wK......
            KBK..wK.....
            KBKRKKKKKKK.
            KBKRXXXXXXXK
            KBKRKKKKKKK.
            KBK..wK.....
            KBK.wK......
            KBKwK.......
            KBbK........
            .KK.........
            """
        )
    }

    val PRAYER by lazy {
        parse(
            """
            .....KK.....
            ....KWWK....
            ....KWWK....
            .KK.KWWK.KK.
            .KWKWWWWKWK.
            KWWWWYYWWWWK
            KWWWWYYWWWWK
            .KWKWWWWKWK.
            .KK.KWWK.KK.
            ....KWWK....
            ....KWWK....
            .....KK.....
            """
        )
    }

    val MAGIC by lazy {
        parse(
            """
            .......KK...
            ......KUuK..
            .....KUUK...
            .....KUUK...
            ....KUYUuK..
            ....KUUUuK..
            ...KUUUUUuK.
            ...KUUYUUuK.
            ..KUUUUUUUuK
            KKKKKKKKKKKK
            KUUUUUUUUUUK
            .KKKKKKKKKK.
            """
        )
    }

    val RUNECRAFT by lazy {
        parse(
            """
            ...KKKKKK...
            ..KwwwwwgK..
            .KwwwwwwwgK.
            KwwwYYYYwwgK
            KwwYwwwwYwgK
            KwwwwYYwwwgK
            KwwwwYYwwwgK
            KwwYwwwwYwgK
            KwwwYYYYwwgK
            .KgwwwwwwgK.
            ..KggggggK..
            ...KKKKKK...
            """
        )
    }

    val CONSTRUCTION by lazy {
        parse(
            """
            ....KKK.....
            ...KXXXK....
            ..KXXXXxK...
            .KXXXXxxKK..
            .KxXXxxKbbK.
            ..KxxxKbbK..
            ...KKKbbK...
            .....KbbK...
            ....KbbK....
            ...KbbK.....
            ..KbbK......
            ..KKK.......
            """
        )
    }

    val HITPOINTS by lazy {
        parse(
            """
            .KKK...KKK.
            KRPRK.KRRrK
            KPRRRKRRRrK
            KRRRRRRRRrK
            KRRRRRRRRrK
            .KRRRRRRrK.
            ..KRRRRrK..
            ...KRRrK...
            ....KrK....
            .....K.....
            """
        )
    }

    val AGILITY by lazy {
        parse(
            """
            .......KK...
            ......KUUK..
            ......KUUK..
            ...KKKKUK...
            ..KUUUUUUK..
            .KUK.KUUKUK.
            .KK..KUUK.KK
            ....KUUUK...
            ...KUKKUK...
            ..KUK..KUK..
            .KUK....KUK.
            .KK......KK.
            """
        )
    }

    val HERBLORE by lazy {
        parse(
            """
            ...KKKK...
            ...KBBK...
            ...KKKK...
            ...KwWK...
            ..KwWWwK..
            .KwWWWWwK.
            KwNNNNNNwK
            KNLNNNNNnK
            KNNNNNNNnK
            KNNNNNNNnK
            .KnNNNNnK.
            ..KKKKKK..
            """
        )
    }

    val THIEVING by lazy {
        parse(
            """
            .KKKK..KKKK.
            KMMMMKKMMMMK
            KMKKMMMMKKMK
            KMKKMmmMKKMK
            KMMMMKKMMMMK
            .KMMK..KMMK.
            ..KK....KK..
            """
        )
    }

    val CRAFTING by lazy {
        parse(
            """
            ..KKKKKKK..
            .KPRRRRRrK.
            KPPRRRRRrrK
            KKKKKKKKKKK
            .KRRRRRRrK.
            ..KRRRRrK..
            ...KRRrK...
            ....KrK....
            .....K.....
            """
        )
    }

    val FLETCHING by lazy {
        parse(
            """
            ..........KK
            ........KKXK
            .......KXXK.
            ......KbXK..
            .....KbbK...
            ....KbbK....
            ...KbbK.....
            .KKbbK......
            KNKbK.......
            KNNKK.......
            .KNNK.......
            ..KK........
            """
        )
    }

    val SLAYER by lazy {
        parse(
            """
            ..KKKKKKK..
            .KEEEEEEEK.
            KEEEEEEEEeK
            KEEEEEEEEeK
            KEKKKEKKKeK
            KEKKKEKKKeK
            KEEEEKEEEeK
            .KEEKKKEeK.
            ..KEEEEEK..
            ..KEKEKeK..
            ..KKKKKKK..
            """
        )
    }

    val HUNTER by lazy {
        parse(
            """
            ..KK...KK..
            .KBBK.KBBK.
            .KBBK.KBBK.
            ..KK...KK..
            KK..KKK..KK
            KBKKBBBKKBK
            .KKBBBBBKK.
            ..KBBBBBK..
            ..KBBBBBK..
            ...KBBBK...
            ....KKK....
            """
        )
    }

    val MINING by lazy {
        parse(
            """
            ..KKKKKKKK..
            .KXXXXXXXXK.
            KXxKKbbKKxXK
            KxK.KbbK.KxK
            KK..KbbK..KK
            ....KbbK....
            ....KbbK....
            ....KbbK....
            ....KbbK....
            ....KbbK....
            ....KbbK....
            ....KKKK....
            """
        )
    }

    val SMITHING by lazy {
        parse(
            """
            KKKKKKKKKKKK
            KwXXXXXXXXxK
            .KKxxxxxxKKK
            ...KxxxxK...
            ...KxxxxK...
            ..KxxxxxxK..
            .KxxxxxxxxK.
            .KKKKKKKKKK.
            """
        )
    }

    val FISHING by lazy {
        parse(
            """
            ...KKKKK....
            .KKCCCCCK.KK
            KCCKCCCCCKCK
            KCCCCCCCCCcK
            KcCCCCCCCKcK
            .KccCCCcK.KK
            ...KKKKK....
            """
        )
    }

    val COOKING by lazy {
        parse(
            """
            ...KK..KK...
            ..KWWKKWWK..
            .KWWWWWWWWK.
            KWWWWWWWWWwK
            KWWWWWWWWWwK
            .KWWWWWWWwK.
            ..KWWWWWWK..
            ..KwwwwwwK..
            ..KWWWWWWK..
            ..KKKKKKKK..
            """
        )
    }

    val FIREMAKING by lazy {
        parse(
            """
            ....KK....
            ...KYK....
            ...KYYK...
            ..KYOYK.K.
            ..KYOOYKYK
            .KYOROYYOK
            KYORROOYOK
            KYORRROOYK
            KYORRRROYK
            .KYORROYK.
            ..KYOOYK..
            ...KKKK...
            """
        )
    }

    val WOODCUTTING by lazy {
        parse(
            """
            ....KKKK....
            ..KKNNNNKK..
            .KNNLNNNNnK.
            KNLNNNNNNNnK
            KNNNNNLNNNnK
            KNNNNNNNNnnK
            .KnNNNNNnnK.
            ..KKnnnnKK..
            ....KbbK....
            ....KbbK....
            ...KbbbbK...
            ..KKKKKKKK..
            """
        )
    }

    val FARMING by lazy {
        parse(
            """
            .KK.....KK.
            KLNK...KNLK
            KNLNK.KNLNK
            .KNNNKNNNK.
            ..KKKNKKK..
            ....KNK....
            ..KKKKKKK..
            .KBBBBBBBK.
            .KbBBBBBbK.
            ..KbBBBbK..
            ..KbbbbbK..
            ..KKKKKKK..
            """
        )
    }

    fun skill(s: Skill): Sprite = when (s) {
        Skill.ATTACK -> ATTACK
        Skill.HITPOINTS -> HITPOINTS
        Skill.MINING -> MINING
        Skill.STRENGTH -> STRENGTH
        Skill.AGILITY -> AGILITY
        Skill.SMITHING -> SMITHING
        Skill.DEFENCE -> DEFENCE
        Skill.HERBLORE -> HERBLORE
        Skill.FISHING -> FISHING
        Skill.RANGED -> RANGED
        Skill.THIEVING -> THIEVING
        Skill.COOKING -> COOKING
        Skill.PRAYER -> PRAYER
        Skill.CRAFTING -> CRAFTING
        Skill.FIREMAKING -> FIREMAKING
        Skill.MAGIC -> MAGIC
        Skill.FLETCHING -> FLETCHING
        Skill.WOODCUTTING -> WOODCUTTING
        Skill.RUNECRAFT -> RUNECRAFT
        Skill.SLAYER -> SLAYER
        Skill.FARMING -> FARMING
        Skill.CONSTRUCTION -> CONSTRUCTION
        Skill.HUNTER -> HUNTER
    }

    // ------------------------------------------------------------------ side panel tabs

    val STATS by lazy {
        parse(
            """
            .........KKK
            .........KYK
            .....KKK.KYK
            .....KYK.KYK
            .KKK.KYK.KYK
            .KYK.KYK.KYK
            .KYK.KYK.KYK
            KKKKKKKKKKKK
            """
        )
    }

    val SCROLL by lazy {
        parse(
            """
            ..KKKKKKKK..
            .KEEEEEEEEK.
            KEKEEEEEEEK.
            KEKEgggggEK.
            .KKEEEEEEEK.
            ..KEgggggEK.
            ..KEEEEEEEK.
            ..KEgggggEKK
            ..KEEEEEEKEK
            ..KEEEEEEKEK
            ...KKKKKKKK.
            """
        )
    }

    val BAG by lazy {
        parse(
            """
            ....KKKK....
            ...KbKKbK...
            ..KKKKKKKK..
            .KBBBBBBBbK.
            KBBTBBBBBBbK
            KBBBBBBBBBbK
            KBKKKKKKKBbK
            KBKyyyyyKBbK
            KBKKKKKKKBbK
            KBBBBBBBBBbK
            .KbbbbbbbbK.
            ..KKKKKKKK..
            """
        )
    }

    val HELMET by lazy {
        parse(
            """
            ...KKKKKK...
            ..KXXXXXxK..
            .KXXXXXXXxK.
            KXXXXXXXXXxK
            KXXKKKKKKXxK
            KXXKKKKKKXxK
            KXXXXKKXXXxK
            KXXXXKKXXXxK
            KxXXXKKXXxxK
            .KxxxKKxxxK.
            ..KKK..KKK..
            """
        )
    }

    val BANNER by lazy {
        parse(
            """
            KK..........
            KbKKKKKKKK..
            KbKRRRRRRRK.
            KbKRRYYRRRRK
            KbKRYRRYRRK.
            KbKRRYYRRRRK
            KbKRRRRRRRK.
            KbKKKKKKKK..
            Kb..........
            Kb..........
            Kb..........
            KK..........
            """
        )
    }

    val SMILEY by lazy {
        parse(
            """
            ...KKKKK...
            ..KYYYYYK..
            .KYYYYYYyK.
            KYYKYYYKYyK
            KYYKYYYKYyK
            KYYYYYYYYyK
            KYKYYYYYKyK
            KYYKKKKKYyK
            .KYYYYYYyK.
            ..KyyyyyK..
            ...KKKKK...
            """
        )
    }

    val PERSON by lazy {
        parse(
            """
            ....KKKK....
            ...KSSSSK...
            ...KSSSsK...
            ...KSSSsK...
            ....KSsK....
            ..KKKKKKKK..
            .KNNNNNNNnK.
            KNNNNNNNNNnK
            KNNNNNNNNNnK
            KKKKKKKKKKKK
            """
        )
    }

    val DOOR by lazy {
        parse(
            """
            .KKKKKKKK.
            KbBBBBBBbK
            KbBbbbbBbK
            KbBbBBbBbK
            KbBbBBbBbK
            KbBbbbbBbK
            KbBBBBYBbK
            KbBbbbbBbK
            KbBbBBbBbK
            KbBbBBbBbK
            KbBbbbbBbK
            KKKKKKKKKK
            """
        )
    }

    val GEAR by lazy {
        parse(
            """
            ....KKKK....
            .KK.KwwK.KK.
            KwwKwwwwKwwK
            .KwwwwwwwwK.
            .KwwwKKwwwK.
            KwwwK..KwwwK
            KwwwK..KwwwK
            .KwwwKKwwwK.
            .KwwwwwwwwK.
            KwwKwwwwKwwK
            .KK.KwwK.KK.
            ....KKKK....
            """
        )
    }

    val CHEER by lazy {
        parse(
            """
            .KK..KK..KK.
            KYK.KYYK.KYK
            KYK.KYYK.KYK
            .KYKKKKKKYK.
            ..KYYYYYYK..
            ...KYYYYK...
            ...KYYYYK...
            ...KYKKYK...
            ..KYK..KYK..
            ..KYK..KYK..
            .KYK....KYK.
            .KK......KK.
            """
        )
    }

    val NOTE by lazy {
        parse(
            """
            ....KK......
            ....KYKK....
            ....KYYYKK..
            ....KYKKYYK.
            ....KYK.KKYK
            ....KYK...KK
            ....KYK.....
            .KKKKYK.....
            KYYYYYK.....
            KYYYYYK.....
            KyYYYyK.....
            .KKKKK......
            """
        )
    }

    // ------------------------------------------------------------------ misc

    val BOOT by lazy {
        parse(
            """
            ..KKKK....
            ..KBBK....
            ..KBBK....
            ..KBBK....
            ..KBBK....
            ..KBBKKK..
            .KBBBBBBK.
            KBBBBBBBBK
            KKKKKKKKKK
            """
        )
    }

    val EYE by lazy {
        parse(
            """
            ...KKKKKK...
            .KKWWWWWWKK.
            KWWWKUUKWWWK
            KWWKUKKUKWWK
            KWWWKUUKWWWK
            .KKWWWWWWKK.
            ...KKKKKK...
            """
        )
    }

    val MAGNIFIER by lazy {
        parse(
            """
            ..KKKK....
            .KCCCCK...
            KCWCCCCK..
            KCCCCCCK..
            KCCCCCCK..
            .KCCCCKK..
            ..KKKKbbK.
            ......KbbK
            .......KbK
            ........KK
            """
        )
    }

    val CAPE by lazy {
        parse(
            """
            .KKKKKKKK.
            KRRRRRRRRK
            KRRRRRRRrK
            KRRRRRRRrK
            KRRRRRRRrK
            KRRRRRRRrK
            KRRRRRRrrK
            KRRRRRRrrK
            KKKKKKKKKK
            """
        )
    }

    val AMULET by lazy {
        parse(
            """
            .KK.....KK.
            .KyK...KyK.
            ..KyK.KyK..
            ...KyKyK...
            ....KKK....
            ...KCCCK...
            ...KCWCK...
            ...KCCCK...
            ....KKK....
            """
        )
    }

    val PLATEBODY by lazy {
        parse(
            """
            .KKK..KKK.
            KXXXKKXXxK
            KXXXXXXXxK
            KXKXXXXKxK
            KXKXXXXKxK
            KKKXXXXKKK
            ..KXXXXK..
            ..KXXXXK..
            ..KxxxxK..
            ..KKKKKK..
            """
        )
    }

    val LEGS by lazy {
        parse(
            """
            KKKKKKKKK
            KXXXXXXxK
            KXXXKXXxK
            KXXK.KXxK
            KXXK.KXxK
            KXXK.KXxK
            KXXK.KXxK
            KxxK.KxxK
            KKKK.KKKK
            """
        )
    }

    val GLOVE by lazy {
        parse(
            """
            ..KKKKK...
            .KBBBBBK..
            KKBBBBBKK.
            KBKBBBBKBK
            KBBBBBBBBK
            .KBBBBBBK.
            ..KBBBBK..
            ..KBBBBK..
            ..KKKKKK..
            """
        )
    }

    val RING by lazy {
        parse(
            """
            ...KKK...
            ..KCWCK..
            ..KKKKK..
            .KyK.KyK.
            KyK...KyK
            KyK...KyK
            .KyKKKyK.
            ..KKKKK..
            """
        )
    }

    val ARROWS by lazy { FLETCHING }

    val CHEST by lazy {
        parse(
            """
            ..KKKKKKKKKKKK..
            .KBBBBBBBBBBBBK.
            KBTBBBBBBBBBBBbK
            KByyyyyyyyyyyybK
            KKKKKKKKKKKKKKKK
            KBBBBBBKKBBBBBbK
            KBBBBBKYYKBBBBbK
            KyyyyyKYYKyyyybK
            KBBBBBBKKBBBBBbK
            KBBBBBBBBBBBBBbK
            KbbbbbbbbbbbbbbK
            KKKKKKKKKKKKKKKK
            """
        )
    }

    val PET by lazy {
        parse(
            """
            .K....K.
            KgK..KgK
            KggKKggK
            KgKggKgK
            KggPPggK
            .KggggK.
            KggggggK
            KggggggK
            .KKKKKK.
            """
        )
    }

    /** Everything, for the sprite-sheet preview. */
    fun all(): List<Pair<String, Sprite>> = Skill.values().map { it.title to skill(it) } + listOf(
        "Stats" to STATS, "Scroll" to SCROLL, "Bag" to BAG, "Helmet" to HELMET, "Banner" to BANNER,
        "Smiley" to SMILEY, "Person" to PERSON, "Door" to DOOR, "Gear" to GEAR, "Cheer" to CHEER,
        "Note" to NOTE, "Boot" to BOOT, "Eye" to EYE, "Magnifier" to MAGNIFIER, "Cape" to CAPE,
        "Amulet" to AMULET, "Platebody" to PLATEBODY, "Legs" to LEGS, "Glove" to GLOVE, "Ring" to RING,
        "Chest" to CHEST, "Pet" to PET,
    )
}
