package com.ramong.runelauncher.core

import android.app.ActivityManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent

/** Battery, storage, memory and volume - shown on the orbs next to the minimap. */
class DeviceStats(private val ctx: Context) {
    var battery = 100
    var charging = false
    var storageFreePct = 100
    var storageFreeGb = 0f
    var storageTotalGb = 0f
    var ramFreePct = 100
    var ramTotalGb = 0f
    var volumePct = 50

    fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) battery = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun refresh() {
        try {
            val fs = StatFs(Environment.getDataDirectory().path)
            val total = fs.totalBytes.toFloat()
            val free = fs.availableBytes.toFloat()
            if (total > 0) {
                storageFreePct = (free * 100 / total).toInt()
                storageFreeGb = free / 1e9f
                storageTotalGb = total / 1e9f
            }
        } catch (e: Exception) {
            // Leave the previous values.
        }
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            if (mi.totalMem > 0) {
                ramFreePct = (mi.availMem * 100 / mi.totalMem).toInt()
                ramTotalGb = mi.totalMem / 1e9f
            }
        } catch (e: Exception) {
            // Leave the previous values.
        }
        try {
            val audio = ctx.getSystemService(AudioManager::class.java) ?: return
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) volumePct = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        } catch (e: Exception) {
            // Leave the previous values.
        }
    }
}

/** Every "do something with the phone" action: intents, the torch, media keys. */
class SystemActions(private val ctx: Context) {
    var torchOn = false
        private set
    private var torchId: String? = null
    private var torchCallback: CameraManager.TorchCallback? = null

    fun start(i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    fun settings(action: String): Boolean = start(Intent(action)) || start(Intent(Settings.ACTION_SETTINGS))

    fun category(cat: String): Boolean = start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat))

    fun camera() = start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) || start(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
    fun dial() = start(Intent(Intent.ACTION_DIAL))
    fun alarms() = start(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    fun calendar() = category(Intent.CATEGORY_APP_CALENDAR)
    fun maps() = category(Intent.CATEGORY_APP_MAPS) || start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
    fun url(u: String) = start(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    fun webSearch(q: String) = start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)) ||
        url("https://www.google.com/search?q=" + Uri.encode(q))
    fun assistant() = start(Intent(Intent.ACTION_VOICE_COMMAND)) || start(Intent(Intent.ACTION_ASSIST))
    fun homeSettings() = settings(Settings.ACTION_HOME_SETTINGS)
    fun wallpaper() = start(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose a wallpaper"))
    fun internetPanel() = if (Build.VERSION.SDK_INT >= 29) {
        start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) || settings(Settings.ACTION_WIFI_SETTINGS)
    } else {
        settings(Settings.ACTION_WIFI_SETTINGS)
    }

    fun media(keyCode: Int) {
        try {
            val audio = ctx.getSystemService(AudioManager::class.java) ?: return
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            // No media session around - nothing to control.
        }
    }

    fun showVolume() {
        try {
            ctx.getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            // Ignore.
        }
    }

    fun initTorch(onChange: () -> Unit) {
        try {
            val cm = ctx.getSystemService(CameraManager::class.java) ?: return
            torchId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchId) {
                        torchOn = enabled
                        onChange()
                    }
                }
            }
            torchCallback = cb
            cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            torchId = null
        }
    }

    fun disposeTorch() {
        try {
            torchCallback?.let { ctx.getSystemService(CameraManager::class.java)?.unregisterTorchCallback(it) }
        } catch (e: Exception) {
            // Ignore.
        }
    }

    fun hasTorch() = torchId != null

    fun toggleTorch(): Boolean {
        val id = torchId ?: return false
        return try {
            ctx.getSystemService(CameraManager::class.java)?.setTorchMode(id, !torchOn) != null
        } catch (e: Exception) {
            false
        }
    }

    /** Hidden but long-standing API; every popular launcher uses it for swipe-down. */
    fun expandNotifications() {
        try {
            val sbm = ctx.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (t: Throwable) {
            // Not allowed on this device.
        }
    }

    fun isDefaultHome(): Boolean = try {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
    } catch (e: Exception) {
        false
    }

    /** Packages of the default phone, messages, camera, browser... for the starter inventory. */
    fun defaultPackages(): List<String> {
        val pm = ctx.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
            Intent(Settings.ACTION_SETTINGS),
        )
        return intents.mapNotNull {
            try {
                pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (e: Exception) {
                null
            }
        }.filter { it != "android" && it != ctx.packageName }.distinct() + listOf("com.android.vending")
    }
}
