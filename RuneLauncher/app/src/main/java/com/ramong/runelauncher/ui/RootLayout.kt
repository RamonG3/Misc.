package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.MenuOption
import com.ramong.runelauncher.core.Skill
import kotlin.math.max
import kotlin.math.min

/**
 * Lays out the game view, chatbox and side panel (portrait: stacked; landscape: the classic
 * fixed-mode arrangement), hosts the bank and menu overlays, and implements [Game.Ui].
 */
@SuppressLint("ViewConstructor")
class RootLayout(context: Context, private val game: Game) : ViewGroup(context), Game.Ui {
    val gameView = GameView(context, game)
    val chatbox = ChatboxView(context, game)
    val side = SidePanelView(context, game)
    val bank = BankView(context, game)
    val menu = MenuView(context)
    private val input = RsInput(context, game)

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0
    private var imeVisible = false

    private val gameRect = RectF()
    private val chatRect = RectF()
    private val sideRect = RectF()
    private val fillRect = RectF()

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        addView(input)
        addView(gameView)
        addView(chatbox)
        addView(side)
        addView(bank)
        addView(menu)
        bank.visibility = INVISIBLE
        menu.visibility = INVISIBLE
        game.ui = this
        setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
    }

    /** For tests and previews, where there is no window to supply insets. */
    fun setInsets(top: Int, bottom: Int) {
        insetTop = top
        insetBottom = bottom
        gameView.topInset = top
        bank.setInsets(top, bottom)
        requestLayout()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(ins: WindowInsets) {
        val wasVisible = imeVisible
        if (Build.VERSION.SDK_INT >= 30) {
            val sys = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = sys.top
            insetBottom = sys.bottom
            insetLeft = sys.left
            insetRight = sys.right
            imeBottom = ins.getInsets(WindowInsets.Type.ime()).bottom
            imeVisible = ins.isVisible(WindowInsets.Type.ime())
        } else {
            insetTop = ins.systemWindowInsetTop
            insetBottom = ins.stableInsetBottom
            insetLeft = ins.systemWindowInsetLeft
            insetRight = ins.systemWindowInsetRight
            imeBottom = ins.systemWindowInsetBottom
            imeVisible = imeBottom > insetBottom + Rs.dp(80f)
        }
        // Keyboard dismissed with its own button while answering a question: treat as cancel.
        if (wasVisible && !imeVisible && game.inputMode == Game.InputMode.PROMPT) post { game.cancelInput() }
        gameView.topInset = insetTop
        bank.setInsets(insetTop, max(insetBottom, imeBottom))
        requestLayout()
    }

    private fun computeRects(w: Float, h: Float) {
        val top = insetTop.toFloat()
        val bottom = h - insetBottom
        val chatH = Rs.dp(134f)
        if (w > h * 1.15f) {
            val sideW = min(w * 0.42f, Rs.dp(380f))
            sideRect.set(w - insetRight - sideW, top, w - insetRight, bottom)
            chatRect.set(insetLeft.toFloat(), bottom - chatH, sideRect.left, bottom)
            gameRect.set(insetLeft.toFloat(), 0f, sideRect.left, chatRect.top)
            return
        }
        val f = Rs.px * 3
        val tabH = min(Rs.dp(44f), (w - 2 * f) / 7f * 0.78f)
        val chrome = 2 * tabH + 2 * f + Rs.dp(8f)
        val idealRow = (w - 2 * f - Rs.dp(8f)) / 4f * 0.56f
        var sideH = chrome + 7 * idealRow
        val maxSide = bottom - chatH - top - Rs.dp(250f)
        if (sideH > maxSide) sideH = max(maxSide, chrome + 7 * Rs.dp(42f))
        sideRect.set(insetLeft.toFloat(), bottom - sideH, w - insetRight, bottom)
        chatRect.set(insetLeft.toFloat(), sideRect.top - chatH, w - insetRight, sideRect.top)
        gameRect.set(insetLeft.toFloat(), 0f, w - insetRight, chatRect.top)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        computeRects(w.toFloat(), h.toFloat())
        fun exact(v: Float) = MeasureSpec.makeMeasureSpec(v.toInt(), MeasureSpec.EXACTLY)
        gameView.measure(exact(gameRect.width()), exact(gameRect.height()))
        chatbox.measure(exact(chatRect.width()), exact(chatRect.height()))
        side.measure(exact(sideRect.width()), exact(sideRect.height()))
        bank.measure(exact(w.toFloat()), exact(h.toFloat()))
        menu.measure(exact(w.toFloat()), exact(h.toFloat()))
        input.measure(exact(1f), exact(1f))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        fun place(v: View, rect: RectF) = v.layout(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        place(gameView, gameRect)
        place(chatbox, chatRect)
        place(side, sideRect)
        bank.layout(0, 0, r - l, b - t)
        menu.layout(0, 0, r - l, b - t)
        input.layout(0, 0, 1, 1)
    }

    override fun onDraw(c: Canvas) {
        // Stone under the navigation bar (and beside it in landscape) instead of bare wallpaper.
        fillRect.set(0f, sideRect.bottom, width.toFloat(), height.toFloat())
        Rs.stone(c, fillRect)
        if (insetRight > 0) {
            fillRect.set(sideRect.right, 0f, width.toFloat(), height.toFloat())
            Rs.stone(c, fillRect)
        }
    }

    // ------------------------------------------------------------------ Game.Ui

    override val bankOpen: Boolean get() = bank.visibility == VISIBLE
    override val currentTab: Int get() = side.tab.ordinal

    override fun invalidateAll() {
        gameView.invalidate()
        chatbox.invalidate()
        side.invalidate()
        if (bankOpen) bank.invalidate()
        if (menu.showing) menu.invalidate()
    }

    override fun tick() {
        gameView.tick()
        if (side.tab == Tab.COMBAT || side.tab == Tab.PRAYER || side.tab == Tab.ACCOUNT) side.invalidate()
    }

    override fun openBank(search: Boolean, skill: Skill?) {
        menu.hide()
        bank.open(skill)
    }

    override fun closeBank() {
        if (game.inputMode == Game.InputMode.SEARCH) game.cancelInput()
        bank.visibility = INVISIBLE
        invalidateAll()
    }

    override fun bankRefresh() {
        if (bankOpen) bank.refresh()
    }

    override fun showMenu(options: List<MenuOption>, x: Float, y: Float) = menu.show(options, x, y)

    override fun hideMenu(): Boolean = menu.hide()

    override fun openKeyboard(initial: String) = input.open(initial)

    override fun closeKeyboard() {
        input.close()
        requestFocus()
    }

    override fun xpDrop(skill: Skill, amount: Int) = gameView.xpDrop(skill, amount)

    override fun fireworks() = gameView.fireworks()

    override fun overhead(text: String) = gameView.overhead(text)

    override fun emote(name: String): Boolean {
        val e = Emote.find(name) ?: return false
        gameView.emote(e)
        return true
    }

    override fun selectTab(index: Int) = side.select(Tab.values()[index])

    override fun worldHop() = gameView.worldHop()

    override fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/** An invisible text field that feeds the chatbox and bank search. */
@SuppressLint("ViewConstructor")
class RsInput(context: Context, private val game: Game) : EditText(context) {
    private var muted = false
    private val imm = context.getSystemService(InputMethodManager::class.java)

    init {
        background = null
        setTextColor(Color.TRANSPARENT)
        isCursorVisible = false
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isFocusableInTouchMode = true
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!muted) game.onInputChanged(s?.toString() ?: "")
            }
        })
        setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) game.submitInput()
            true
        }
    }

    fun open(initial: String) {
        muted = true
        setText(initial)
        setSelection(text.length)
        muted = false
        requestFocus()
        post {
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            if (Build.VERSION.SDK_INT >= 30) windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    fun close() {
        imm?.hideSoftInputFromWindow(windowToken, 0)
        muted = true
        setText("")
        muted = false
    }
}
