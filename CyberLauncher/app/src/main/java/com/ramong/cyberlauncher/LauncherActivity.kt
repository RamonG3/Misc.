package com.ramong.cyberlauncher

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
import com.ramong.cyberlauncher.core.Curves
import com.ramong.cyberlauncher.core.Game
import com.ramong.cyberlauncher.core.LauncherAppsSource
import com.ramong.cyberlauncher.core.SaveData
import com.ramong.cyberlauncher.core.SystemActions
import com.ramong.cyberlauncher.ui.Cy
import com.ramong.cyberlauncher.ui.RootLayout

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cy.init(this)
        source = LauncherAppsSource(this)
        game = Game(this, source, SaveData(this), SystemActions(this))
        root = RootLayout(this, game)
        setContentView(root)
        setupWindow()

        source.listen {
            // An update can change an app's icon without changing its key.
            game.icons.clear()
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
     * Screenshot hooks for CI: `am start -n ... --es demo stash`. Debug builds only, and
     * they only move the UI around.
     */
    private fun demo(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val cmd = intent?.getStringExtra("demo") ?: return
        root.boot.visibility = View.INVISIBLE
        when {
            cmd == "seed" -> game.demoSeed()
            cmd == "stash" -> game.ui.openStash(null, null)
            cmd == "home" -> game.homePressed()
            cmd == "levelup" -> game.award(null, (Curves.xpForLevel(game.level() + 1) - game.save.xp).toInt().coerceAtLeast(1))
            cmd == "lifepath" -> game.ui.boot(true)
            cmd == "breach" -> game.ui.breach()
            cmd.startsWith("tab:") -> game.ui.selectTab(cmd.removePrefix("tab:").toIntOrNull() ?: 0)
            cmd.startsWith("scan:") -> game.apps.getOrNull(cmd.removePrefix("scan:").toIntOrNull() ?: 0)?.let { game.scan(it) }
            cmd.startsWith("menu:") -> game.inventoryMenu(cmd.removePrefix("menu:").toIntOrNull() ?: 0, root.width / 2f, root.height * 0.6f)
            cmd.startsWith("theme:") -> game.command("/theme " + cmd.removePrefix("theme:"))
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
        game.back()
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
