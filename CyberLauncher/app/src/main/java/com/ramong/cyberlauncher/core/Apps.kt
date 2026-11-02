package com.ramong.cyberlauncher.core

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One launchable activity. [key] is stable across restarts and is what gets saved. */
class AppEntry(
    val key: String,
    val label: String,
    val packageName: String,
    val className: String,
    val category: Int,
    val user: UserHandle?,
    val isWork: Boolean = false,
) {
    val attr: Attr by lazy { AttrMap.attrFor(packageName, label, category) }
    val component: ComponentName get() = ComponentName(packageName, className)
}

/** What a scan finds out about an app. Permission names are the friendly group names. */
class AppIntel(
    val version: String?,
    val installed: Long,
    val updated: Long,
    val targetSdk: Int,
    val system: Boolean,
    val granted: List<String>,
    val denied: List<String>,
    /** 0 = low ... 4 = extreme, from the sensitive permissions it actually holds. */
    val threat: Int,
    /** False when the package couldn't be read (another profile, or it vanished). */
    val readable: Boolean = true,
) {
    companion object {
        val THREATS = listOf("LOW", "MODERATE", "HIGH", "VERY HIGH", "EXTREME")

        private class Group(val name: String, val weight: Int, val perms: List<String>)

        private val groups = listOf(
            Group("LOCATION", 3, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            Group("BACKGROUND LOCATION", 2, listOf("android.permission.ACCESS_BACKGROUND_LOCATION")),
            Group("MICROPHONE", 3, listOf(Manifest.permission.RECORD_AUDIO)),
            Group("CAMERA", 2, listOf(Manifest.permission.CAMERA)),
            Group("CONTACTS", 2, listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS)),
            Group("SMS", 3, listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)),
            Group("CALL LOG", 2, listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)),
            Group("PHONE", 1, listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)),
            Group("CALENDAR", 1, listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
            Group("BODY SENSORS", 2, listOf(Manifest.permission.BODY_SENSORS)),
            Group("ACTIVITY", 1, listOf("android.permission.ACTIVITY_RECOGNITION")),
            Group("NEARBY DEVICES", 1, listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.NEARBY_WIFI_DEVICES")),
            Group("MEDIA", 1, listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", Manifest.permission.READ_EXTERNAL_STORAGE)),
        )

        fun from(info: PackageInfo): AppIntel {
            val req = info.requestedPermissions ?: emptyArray()
            val flags = info.requestedPermissionsFlags ?: IntArray(0)
            val granted = HashSet<String>()
            val asked = HashSet<String>()
            for (i in req.indices) {
                asked += req[i]
                if (i < flags.size && flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) granted += req[i]
            }
            val g = ArrayList<String>()
            val d = ArrayList<String>()
            var score = 0
            for (grp in groups) {
                when {
                    grp.perms.any { it in granted } -> {
                        g += grp.name
                        score += grp.weight
                    }
                    grp.perms.any { it in asked } -> d += grp.name
                }
            }
            val ai = info.applicationInfo
            return AppIntel(
                info.versionName, info.firstInstallTime, info.lastUpdateTime, ai?.targetSdkVersion ?: 0,
                ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, g, d, threatFor(score),
            )
        }

        fun threatFor(score: Int) = when {
            score <= 1 -> 0
            score <= 4 -> 1
            score <= 7 -> 2
            score <= 11 -> 3
            else -> 4
        }
    }
}

/** Where apps come from. The real one wraps [LauncherApps]; previews use a fake. */
interface AppSource {
    fun load(): List<AppEntry>
    fun icon(app: AppEntry): Drawable?
    fun launch(app: AppEntry, bounds: Rect?, options: Bundle?): Boolean
    fun details(app: AppEntry)
    fun uninstall(app: AppEntry)
    fun intel(app: AppEntry): AppIntel?
    /** [onChanged] gets the package that changed, or null when several did. */
    fun listen(onChanged: (String?) -> Unit) {}
    fun dispose() {}
}

class LauncherAppsSource(private val ctx: Context) : AppSource {
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)!!
    private val users = ctx.getSystemService(UserManager::class.java)!!
    private val infos = HashMap<String, LauncherActivityInfo>()
    private var callback: LauncherApps.Callback? = null

    override fun load(): List<AppEntry> {
        val out = ArrayList<AppEntry>()
        val me = Process.myUserHandle()
        synchronized(infos) {
            infos.clear()
            for (user in users.userProfiles) {
                val serial = users.getSerialNumberForUser(user)
                val list = try {
                    launcherApps.getActivityList(null, user)
                } catch (e: Exception) {
                    emptyList()
                }
                for (info in list) {
                    val cn = info.componentName
                    if (cn.packageName == ctx.packageName) continue
                    val key = "${cn.flattenToShortString()}#$serial"
                    infos[key] = info
                    out += AppEntry(
                        key, info.label.toString(), cn.packageName, cn.className,
                        info.applicationInfo.category, user, user != me,
                    )
                }
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    override fun icon(app: AppEntry): Drawable? {
        val info = synchronized(infos) { infos[app.key] } ?: return null
        return try {
            info.getBadgedIcon(ctx.resources.displayMetrics.densityDpi)
        } catch (e: Exception) {
            null
        }
    }

    override fun launch(app: AppEntry, bounds: Rect?, options: Bundle?): Boolean = try {
        launcherApps.startMainActivity(app.component, app.user ?: Process.myUserHandle(), bounds, options)
        true
    } catch (e: Exception) {
        false
    }

    override fun details(app: AppEntry) {
        try {
            launcherApps.startAppDetailsActivity(app.component, app.user ?: Process.myUserHandle(), null, null)
        } catch (e: Exception) {
            // Nothing sensible to do; the caller already printed a feed line.
        }
    }

    override fun uninstall(app: AppEntry) {
        val i = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.user?.let { i.putExtra(Intent.EXTRA_USER, it) }
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            // Some devices refuse to uninstall system apps; the dialog just never shows.
        }
    }

    @Suppress("DEPRECATION")
    override fun intel(app: AppEntry): AppIntel? {
        if (!app.isWork) {
            try {
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    ctx.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                } else {
                    ctx.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                }
                return AppIntel.from(info)
            } catch (e: Exception) {
                // Fall through to the little we can see from another profile.
            }
        }
        return try {
            val ai = launcherApps.getApplicationInfo(app.packageName, 0, app.user ?: Process.myUserHandle())
            AppIntel(null, 0L, 0L, ai.targetSdkVersion, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                emptyList(), emptyList(), 0, readable = false)
        } catch (e: Exception) {
            null
        }
    }

    override fun listen(onChanged: (String?) -> Unit) {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = onChanged(packageName)
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = onChanged(packageName)
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = onChanged(packageName)
            override fun onPackagesAvailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) = onChanged(names?.singleOrNull())
            override fun onPackagesUnavailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) = onChanged(names?.singleOrNull())
        }
        callback = cb
        launcherApps.registerCallback(cb, Handler(Looper.getMainLooper()))
    }

    override fun dispose() {
        callback?.let { launcherApps.unregisterCallback(it) }
        callback = null
    }
}

/**
 * Loads app icons off the main thread and gives them the launcher's look.
 * Style 0 keeps the stock icon, 1 adds scanlines and a red/cyan colour fringe ("chrome"),
 * 2 turns the icon into a hologram tinted with the current palette.
 */
class IconCache(
    private val source: AppSource,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val post: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val onLoaded: () -> Unit,
) {
    var style = 1
        private set
    private var palette: Palette = Content.palettes[0]

    private val cache = HashMap<String, Bitmap>()
    /** Icons being refreshed after an update: shown until the new one is ready. */
    private val stale = HashMap<String, Bitmap>()
    private val pending = HashSet<String>()
    private var notifyQueued = false

    /** Switches look. Old bitmaps are dropped so two full sets never sit in memory. */
    fun configure(style: Int, palette: Palette) {
        if (style == this.style && palette.id == this.palette.id) return
        this.style = style
        this.palette = palette
        cache.clear()
        onLoaded()
    }

    private fun keyOf(app: AppEntry) = "${app.key}@$style${if (style == 2) palette.id else ""}"

    fun get(app: AppEntry): Bitmap? {
        val k = keyOf(app)
        cache[k]?.let { return it }
        if (pending.add(k)) {
            val s = style
            val p = palette
            executor.execute {
                val bmp = try {
                    stylize(source.icon(app), s, p)
                } catch (e: Throwable) {
                    null
                }
                post(Runnable {
                    pending.remove(k)
                    stale.remove(k)
                    if (bmp != null && k == keyOf(app)) cache[k] = bmp
                    if (!notifyQueued) {
                        notifyQueued = true
                        post(Runnable {
                            notifyQueued = false
                            onLoaded()
                        })
                    }
                })
            }
        }
        return cache[k] ?: stale[k]
    }

    /** One package changed (usually an update): reload just its icons, keeping the old ones meanwhile. */
    fun refresh(packageName: String) {
        val keys = cache.keys.filter { it.startsWith("$packageName/") }
        for (k in keys) cache.remove(k)?.let { stale[k] = it }
    }

    fun clear() {
        cache.clear()
        stale.clear()
    }

    companion object {
        const val SIZE = 128

        fun render(d: Drawable?, size: Int, inset: Int = 0): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            if (d != null) {
                val c = Canvas(bmp)
                d.setBounds(inset, inset, size - inset, size - inset)
                d.draw(c)
            }
            return bmp
        }

        fun stylize(d: Drawable?, style: Int, p: Palette): Bitmap = when (style) {
            0 -> render(d, SIZE)
            2 -> holo(render(d, SIZE, SIZE / 16), p)
            else -> chrome(render(d, SIZE, SIZE / 16))
        }

        private fun scanlines(period: Int, color: Int): Paint {
            val pat = IntArray(period)
            pat[period - 1] = color
            val tile = Bitmap.createBitmap(pat, 1, period, Bitmap.Config.ARGB_8888)
            return Paint().apply {
                shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            }
        }

        /** Stock colours, slightly desaturated, with a red/cyan fringe and faint scanlines. */
        fun chrome(base: Bitmap): Bitmap {
            val n = base.width
            val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val o = n / 40f
            val tint = Paint(Paint.FILTER_BITMAP_FLAG)
            tint.colorFilter = PorterDuffColorFilter(0xFF00E5FF.toInt(), PorterDuff.Mode.SRC_IN)
            tint.alpha = 170
            c.drawBitmap(base, -o, 0f, tint)
            tint.colorFilter = PorterDuffColorFilter(0xFFFF2A55.toInt(), PorterDuff.Mode.SRC_IN)
            c.drawBitmap(base, o, 0f, tint)
            val main = Paint(Paint.FILTER_BITMAP_FLAG)
            main.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.82f) })
            c.drawBitmap(base, 0f, 0f, main)
            c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), scanlines(4, 0x38000000))
            return out
        }

        /** Luminance mapped onto dark -> primary -> near white, with hologram scan gaps. */
        fun holo(base: Bitmap, p: Palette): Bitmap {
            val n = base.width
            val px = IntArray(n * n)
            base.getPixels(px, 0, n, 0, 0, n, n)
            val dark = mix(p.primary, 0xFF000000.toInt(), 0.72f)
            val light = mix(p.primary, 0xFFFFFFFF.toInt(), 0.7f)
            for (y in 0 until n) {
                val gap = y % 3 == 2
                for (x in 0 until n) {
                    val i = y * n + x
                    val col = px[i]
                    val a = col ushr 24
                    if (a == 0) continue
                    val l = (0.3f * Color.red(col) + 0.59f * Color.green(col) + 0.11f * Color.blue(col)) / 255f
                    val t = (l * 1.15f).coerceIn(0f, 1f)
                    val rgb = if (t < 0.5f) mix(dark, p.primary, t * 2f) else mix(p.primary, light, (t - 0.5f) * 2f)
                    val alpha = (a * (if (gap) 0.45f else 0.95f)).toInt()
                    px[i] = (rgb and 0x00FFFFFF) or (alpha shl 24)
                }
            }
            return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return Color.argb(
                255,
                (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
            )
        }
    }
}
