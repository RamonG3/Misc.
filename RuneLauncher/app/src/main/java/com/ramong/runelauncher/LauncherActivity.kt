package com.ramong.runelauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.ramong.runelauncher.core.Game
import com.ramong.runelauncher.core.LauncherAppsSource
import com.ramong.runelauncher.core.SaveData
import com.ramong.runelauncher.core.SystemActions
import com.ramong.runelauncher.ui.RootLayout
import com.ramong.runelauncher.ui.Rs

/** The home screen. Everything interesting lives in [Game] and the views under [RootLayout]. */
class LauncherActivity : Activity() {
    private lateinit var game: Game
    private lateinit var root: RootLayout
    private lateinit var source: LauncherAppsSource
    private var foreground = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            game.stats.onBattery(intent)
            root.gameView.invalidate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rs.init(this)
        source = LauncherAppsSource(this)
        game = Game(this, source, SaveData(this), SystemActions(this))
        root = RootLayout(this, game)
        setContentView(root)
        setupWindow()

        source.listen {
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
