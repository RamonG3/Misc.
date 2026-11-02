package com.ramong.runelauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.LauncherAppsSource
import com.ramong.runelauncher.core.SaveData
import com.ramong.runelauncher.core.Skill
import com.ramong.runelauncher.core.SystemActions
import com.ramong.runelauncher.core.Xp
import com.ramong.runelauncher.ui.AppIcon
import com.ramong.runelauncher.ui.HomeView
import com.ramong.runelauncher.ui.R3
import com.ramong.runelauncher.ui.RootLayout

/** The home screen. Everything interesting lives in [Game] and the views under [RootLayout]. */
class LauncherActivity : Activity() {
    private lateinit var game: Game
    private lateinit var root: RootLayout
    private lateinit var source: LauncherAppsSource
    private var foreground = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            game.stats.onBattery(intent)
            game.onBatteryChanged()
        }
    }

    private val debuggable get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        R3.init(this)
        AppIcon.refreshFonts()
        source = LauncherAppsSource(this)
        game = Game(this, source, SaveData(this), SystemActions(this))
        root = RootLayout(this, game)
        setContentView(root)
        setupWindow()

        source.listen { pkg ->
            // An update can change an app's icon without changing its key. Only that app's
            // icon reloads, so the rest of the screen doesn't flicker during nightly updates.
            if (pkg != null) game.icons.refresh(pkg) else game.icons.clear()
            game.reloadApps()
        }
        game.actions.initTorch { root.invalidateAll() }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        sticky?.let { game.stats.onBattery(it) }
        game.start()
        root.requestFocus()
        if (debuggable) HomeView.touchLog = { android.util.Log.d("RuneTouch", it) }
        demo(intent)
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        val w = window
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * Screenshot hooks for CI: `am start -n ... --es demo drawer`. Debug builds only, and they
     * only move the UI around.
     */
    private fun demo(intent: Intent?) {
        if (!debuggable) return
        val cmd = intent?.getStringExtra("demo") ?: return
        when {
            cmd == "seed" -> game.demoSeed()
            cmd == "home" -> game.homePressed()
            cmd == "drawer" -> game.ui.openDrawer(search = false, pick = false)
            cmd == "log" -> game.ui.showPage(Game.PAGE_LOG)
            cmd == "settings" -> game.ui.openSettings()
            cmd == "folder" -> game.save.folders.keys.firstOrNull()?.let { game.ui.openFolder(it) }
            cmd == "homemenu" -> game.homeMenu(root.width / 2f, root.height * 0.45f)
            cmd == "levelup" -> game.award(Skill.WOODCUTTING, (Xp.forLevel(game.level(Skill.WOODCUTTING) + 1) - game.xp(Skill.WOODCUTTING)).toInt().coerceAtLeast(1))
            cmd == "rename" -> game.save.folders.keys.firstOrNull()?.let { game.renameFolder(it) }
            cmd.startsWith("examine:") -> game.apps.getOrNull(cmd.removePrefix("examine:").toIntOrNull() ?: 0)?.let { game.examine(it) }
            cmd.startsWith("menu:") -> {
                val i = cmd.removePrefix("menu:").toIntOrNull() ?: 0
                val slot = Game.Slot(false, i)
                when (val c = game.cell(slot)) {
                    is Game.Cell.App -> game.appMenu(c.app, slot, null, root.width / 2f, root.height * 0.45f)
                    is Game.Cell.Dir -> game.folderMenu(slot, root.width / 2f, root.height * 0.45f)
                    null -> Unit
                }
            }
            cmd.startsWith("theme:") -> game.setTheme(cmd.removePrefix("theme:"))
        }
        root.invalidateAll()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        game.resume()
    }

    override fun onPause() {
        foreground = false
        game.pause()
        super.onPause()
    }

    override fun onStop() {
        game.hidden()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("demo")) {
            demo(intent)
            return
        }
        val home = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        if (home && foreground) game.homePressed()
    }

    @Deprecated("Launchers swallow Back; it only closes our own overlays.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val handled = game.back()
        HomeView.touchLog?.invoke("back -> $handled")
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Already gone.
        }
        source.dispose()
        game.actions.disposeTorch()
        super.onDestroy()
    }
}
