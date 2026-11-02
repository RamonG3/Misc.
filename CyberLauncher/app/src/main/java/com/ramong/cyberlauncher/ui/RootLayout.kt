package com.ramong.cyberlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
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
import com.ramong.cyberlauncher.core.AppEntry
import com.ramong.cyberlauncher.core.Attr
import com.ramong.cyberlauncher.core.Banner
import com.ramong.cyberlauncher.core.Choice
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.ItemMenu
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Stacks the HUD, the feed and the tab panel (side by side in landscape), hosts every overlay,
 * and implements [Game.Ui].
 */
@SuppressLint("ViewConstructor")
class RootLayout(context: Context, private val game: Game) : ViewGroup(context), Game.Ui {
    val hud = HudView(context, game)
    val feed = FeedView(context, game)
    val panel = PanelView(context, game)
    val stash = StashView(context, game)
    val menu = MenuView(context)
    val scan = ScanView(context, game)
    val choice = ChoiceView(context)
    val boot = BootView(context, game)
    private val input = CyInput(context, game)

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0
    private var imeVisible = false

    private val hudRect = RectF()
    private val feedRect = RectF()
    private val panelRect = RectF()

    private var jitterUntil = 0L
    private val jitter = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() < jitterUntil) {
                panel.translationX = (Random.nextFloat() - 0.5f) * Cy.dp(6f)
                feed.translationX = (Random.nextFloat() - 0.5f) * Cy.dp(8f)
                postDelayed(this, 40L)
            } else {
                panel.translationX = 0f
                feed.translationX = 0f
            }
        }
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        addView(input)
        addView(hud)
        addView(feed)
        addView(panel)
        addView(stash)
        addView(menu)
        addView(scan)
        addView(choice)
        addView(boot)
        for (v in listOf(stash, menu, scan, choice, boot)) v.visibility = INVISIBLE
        game.ui = this
        Cy.apply(game.palette)
        setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
    }

    /** For previews, where there is no window to supply insets. */
    fun setInsets(top: Int, bottom: Int) {
        insetTop = top
        insetBottom = bottom
        pushInsets()
        requestLayout()
    }

    private fun pushInsets() {
        hud.topInset = insetTop
        stash.setInsets(insetTop, max(insetBottom, imeBottom))
        scan.setInsets(insetTop, insetBottom)
        choice.setInsets(max(insetBottom, imeBottom))
        boot.setInsets(insetTop, insetBottom)
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
            imeVisible = imeBottom > insetBottom + Cy.dp(80f)
        }
        // Keyboard dismissed with its own button while answering a prompt: treat as cancel.
        if (wasVisible && !imeVisible && game.inputMode == Game.InputMode.PROMPT) post { game.cancelInput() }
        pushInsets()
        requestLayout()
    }

    private fun computeRects(w: Float, h: Float) {
        val top = insetTop.toFloat()
        val bottom = h - insetBottom
        if (w > h * 1.15f) {
            val pw = min(w * 0.46f, Cy.dp(460f))
            panelRect.set(w - insetRight - pw, top, w - insetRight, bottom)
            feedRect.set(insetLeft.toFloat(), bottom - Cy.dp(108f), panelRect.left, bottom)
            hudRect.set(insetLeft.toFloat(), 0f, panelRect.left, feedRect.top)
            return
        }
        val avail = bottom - top
        val chrome = Cy.dp(100f)
        val cellW = (w - insetLeft - insetRight - Cy.dp(32f)) / 4f
        val idealRow = (cellW * 0.8f).coerceIn(Cy.dp(56f), Cy.dp(80f))
        var panelH = chrome + 5 * idealRow
        val maxPanel = avail * 0.52f
        if (panelH > maxPanel) panelH = max(maxPanel, chrome + 5 * Cy.dp(52f))
        val feedH = if (avail < Cy.dp(700f)) Cy.dp(96f) else Cy.dp(118f)
        panelRect.set(insetLeft.toFloat(), bottom - panelH, w - insetRight, bottom)
        feedRect.set(insetLeft.toFloat(), panelRect.top - feedH, w - insetRight, panelRect.top)
        hudRect.set(insetLeft.toFloat(), 0f, w - insetRight, feedRect.top)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        computeRects(w.toFloat(), h.toFloat())
        fun exact(v: Float) = MeasureSpec.makeMeasureSpec(v.toInt(), MeasureSpec.EXACTLY)
        hud.measure(exact(hudRect.width()), exact(hudRect.height()))
        feed.measure(exact(feedRect.width()), exact(feedRect.height()))
        panel.measure(exact(panelRect.width()), exact(panelRect.height()))
        for (v in listOf(stash, menu, scan, choice, boot)) v.measure(exact(w.toFloat()), exact(h.toFloat()))
        input.measure(exact(1f), exact(1f))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        fun place(v: View, rect: RectF) = v.layout(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        place(hud, hudRect)
        place(feed, feedRect)
        place(panel, panelRect)
        for (v in listOf(stash, menu, scan, choice, boot)) v.layout(0, 0, r - l, b - t)
        input.layout(0, 0, 1, 1)
    }

    override fun onDraw(c: Canvas) {
        // The space under the navigation bar, and the HUD's backing behind the feed and panel.
        Cy.rect(c, 0f, feedRect.top, width.toFloat(), height.toFloat(), Cy.withAlpha(Cy.bg, 0xF2))
        if (insetRight > 0) Cy.rect(c, panelRect.right, 0f, width.toFloat(), height.toFloat(), Cy.bg)
    }

    private fun updateCovered() {
        hud.covered = stash.showing || scan.showing || boot.showing
    }

    // ------------------------------------------------------------------ Game.Ui

    override val stashOpen: Boolean get() = stash.showing
    override val currentTab: Int get() = panel.tab

    override fun invalidateAll() {
        updateCovered()
        invalidate()
        hud.invalidate()
        feed.invalidate()
        panel.invalidate()
        if (stash.showing) stash.invalidate()
        if (menu.showing) menu.invalidate()
        if (scan.showing) scan.invalidate()
        if (choice.showing) choice.invalidate()
    }

    override fun tick() {
        hud.tick()
        feed.invalidate()
        if (panel.tab != Game.TAB_INVENTORY) panel.invalidate()
    }

    override fun openStash(attr: Attr?, pick: Game.Pick?) {
        menu.hide()
        stash.open(attr, pick)
        updateCovered()
    }

    override fun closeStash() {
        if (game.inputMode == Game.InputMode.SEARCH) game.cancelInput()
        stash.visibility = INVISIBLE
        updateCovered()
        invalidateAll()
    }

    override fun stashRefresh() {
        if (stash.showing) stash.refresh()
    }

    override fun showMenu(menu: ItemMenu) = this.menu.show(menu)
    override fun hideMenu(): Boolean = menu.hide()

    override fun showScan(app: AppEntry) {
        scan.show(app)
        updateCovered()
    }

    override fun hideScan(): Boolean {
        val r = scan.hide()
        updateCovered()
        return r
    }

    override fun showChoice(choice: Choice) {
        menu.hide()
        this.choice.show(choice)
    }

    override fun hideChoice(): Boolean = choice.hide()

    override fun openKeyboard(initial: String) = input.open(initial)

    override fun closeKeyboard() {
        input.close()
        requestFocus()
    }

    override fun drop(text: String, color: Int) = hud.drop(text, color)
    override fun banner(b: Banner) = hud.banner(b)

    override fun glitch(strength: Float) {
        hud.glitch(strength)
        if (!game.save.glitch && strength < 0.5f) return
        jitterUntil = SystemClock.uptimeMillis() + (120 + strength * 160).toLong()
        removeCallbacks(jitter)
        post(jitter)
    }

    override fun selectTab(index: Int) {
        if (stash.showing) closeStash()
        panel.select(index)
    }

    override fun travel() = hud.travel()

    override fun breach() {
        boot.breach()
        updateCovered()
    }

    override fun boot(lifepathAfter: Boolean) {
        boot.boot(lifepathAfter)
        updateCovered()
    }

    override fun themeChanged() {
        Cy.apply(game.palette)
        hud.themeChanged()
        invalidateAll()
    }

    override fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/** An invisible text field that feeds the stash search and the prompts. */
@SuppressLint("ViewConstructor")
class CyInput(context: Context, private val game: Game) : EditText(context) {
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
            if (event == null || event.action == KeyEvent.ACTION_DOWN) {
                val root = parent as? RootLayout
                game.submitInput(root?.stash?.pick?.takeIf { root.stash.showing })
            }
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
