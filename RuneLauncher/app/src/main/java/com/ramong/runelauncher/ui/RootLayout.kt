package com.ramong.runelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.ramong.runelauncher.core.AppEntry
import com.ramong.runelauncher.core.Banner
import com.ramong.runelauncher.core.Choice
import com.ramong.runelauncher.core.FeedLine
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.ItemMenu
import com.ramong.runelauncher.core.Skill
import kotlin.math.max

/**
 * Stacks the background, the home screen and every overlay (bank, folder, settings, menus,
 * examine, dialogues, notices), and implements [Game.Ui].
 */
@SuppressLint("ViewConstructor")
class RootLayout(context: Context, private val game: Game) : ViewGroup(context), Game.Ui {
    val scene = SceneView(context, game)
    val home = HomeView(context, game)
    val drawer = DrawerView(context, game, home)
    val folder = FolderView(context, game, home)
    val settings = SettingsView(context, game)
    val menu = MenuView(context)
    val examine = ExamineView(context, game)
    val choice = ChoiceView(context)
    val notice = NoticeView(context, game)
    val prompt = PromptView(context, game)
    private val input = RsInput(context, game)
    private val overlays = listOf<View>(drawer, folder, settings, menu, examine, choice)

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0
    private var imeVisible = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(e)
        HomeView.touchLog?.let { log ->
            if (e.actionMasked != MotionEvent.ACTION_MOVE) {
                val shown = overlays.filter { it.visibility == VISIBLE }.joinToString { it.javaClass.simpleName }
                log("root ${MotionEvent.actionToString(e.actionMasked)} handled=$handled visible=[$shown] page=${home.page}")
            }
        }
        return handled
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        addView(input)
        addView(scene)
        addView(home)
        for (v in overlays) addView(v)
        addView(prompt)
        addView(notice)
        for (v in overlays) v.visibility = INVISIBLE
        prompt.visibility = INVISIBLE
        game.ui = this
        R3.apply(game.palette)
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
        home.setInsets(insetTop, insetBottom, insetLeft, insetRight)
        drawer.setInsets(insetTop, max(insetBottom, imeBottom))
        settings.setInsets(insetTop, insetBottom)
        examine.setInsets(insetTop, insetBottom)
        choice.setInsets(max(insetBottom, imeBottom))
        notice.topInset = insetTop
        prompt.bottomInset = max(insetBottom, imeBottom)
        prompt.invalidate()
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
            imeVisible = imeBottom > insetBottom + R3.dp(80f)
        }
        // Keyboard dismissed with its own button while answering a prompt: treat as cancel.
        if (wasVisible && !imeVisible && game.inputMode == Game.InputMode.PROMPT) post { game.cancelInput() }
        pushInsets()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v === input) v.measure(MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY))
            else v.measure(ws, hs)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v === input) v.layout(0, 0, 1, 1) else v.layout(0, 0, r - l, b - t)
        }
        placeNotices()
    }

    private fun placeNotices() {
        notice.toastBottom = if (drawer.showing || settings.showing || examine.showing) {
            height - max(insetBottom, imeBottom) - R3.dp(16f)
        } else {
            home.toastBottom
        }
        notice.dropX = home.dropAnchorX
        notice.dropY = home.dropAnchorY
    }

    private fun updateCovered() {
        scene.covered = drawer.showing || settings.showing || examine.showing
        placeNotices()
    }

    // ------------------------------------------------------------------ Game.Ui

    override val drawerOpen: Boolean get() = drawer.showing
    override val page: Int get() = home.page

    override fun invalidateAll() {
        updateCovered()
        prompt.visibility = if (game.inputMode == Game.InputMode.PROMPT) VISIBLE else INVISIBLE
        prompt.invalidate()
        home.invalidate()
        scene.invalidate()
        notice.invalidate()
        for (v in overlays) if (v.visibility == VISIBLE) v.invalidate()
    }

    override fun tick() {
        scene.tick()
        home.invalidate()
        notice.invalidate()
    }

    override fun openDrawer(search: Boolean, pick: Boolean) {
        menu.hide()
        settings.hide()
        folder.close()
        drawer.open(pick)
        updateCovered()
    }

    override fun closeDrawer() {
        if (game.inputMode == Game.InputMode.SEARCH) game.cancelInput()
        drawer.close()
        game.picking = false
        game.pickFolder = null
        updateCovered()
        invalidateAll()
    }

    override fun drawerRefresh() {
        if (drawer.visibility == VISIBLE) drawer.refresh()
    }

    override fun openSettings() {
        menu.hide()
        if (drawer.showing) closeDrawer()
        folder.close()
        settings.open()
        updateCovered()
    }

    override fun closeSettings(): Boolean {
        val r = settings.hide()
        updateCovered()
        return r
    }

    override fun openFolder(id: String) {
        menu.hide()
        folder.open(id)
    }

    override fun closeFolder(): Boolean = folder.close()

    override fun showMenu(menu: ItemMenu) = this.menu.show(menu)
    override fun hideMenu(): Boolean = menu.hide()

    override fun showExamine(app: AppEntry) {
        examine.show(app)
        updateCovered()
    }

    override fun hideExamine(): Boolean {
        val r = examine.hide()
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

    override fun banner(b: Banner) = notice.banner(b)

    override fun toast(line: FeedLine) {
        placeNotices()
        notice.toast(line)
    }

    override fun xpDrop(skill: Skill, amount: Int) {
        placeNotices()
        notice.xpDrop(skill, amount)
    }

    override fun showPage(page: Int) = home.showPage(page)

    override fun worldHop() {
        scene.worldHop()
        home.worldHop()
    }

    override fun themeChanged() {
        R3.apply(game.palette)
        scene.themeChanged()
        home.themeChanged()
        invalidateAll()
    }

    override fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/** An invisible text field that feeds the app search and the prompts. */
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
