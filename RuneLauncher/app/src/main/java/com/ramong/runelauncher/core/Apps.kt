package com.ramong.runelauncher.core

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
import android.graphics.LinearGradient
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    val skill: Skill by lazy { SkillMap.skillFor(packageName, label, category) }
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
        val THREATS = listOf("Low", "Moderate", "High", "Very high", "Extreme")

        private class Group(val name: String, val weight: Int, val perms: List<String>)

        private val groups = listOf(
            Group("Location", 3, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            Group("Background location", 2, listOf("android.permission.ACCESS_BACKGROUND_LOCATION")),
            Group("Microphone", 3, listOf(Manifest.permission.RECORD_AUDIO)),
            Group("Camera", 2, listOf(Manifest.permission.CAMERA)),
            Group("Contacts", 2, listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS)),
            Group("Text messages", 3, listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)),
            Group("Call log", 2, listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)),
            Group("Phone", 1, listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)),
            Group("Calendar", 1, listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
            Group("Body sensors", 2, listOf(Manifest.permission.BODY_SENSORS)),
            Group("Physical activity", 1, listOf("android.permission.ACTIVITY_RECOGNITION")),
            Group("Nearby devices", 1, listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.NEARBY_WIFI_DEVICES")),
            Group("Photos and media", 1, listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", Manifest.permission.READ_EXTERNAL_STORAGE)),
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
            // Nothing sensible to do; the caller already showed a message.
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
 * Style 0 keeps the stock icon, 1 sets it in a gold rim with a soft shadow (made for Pixel's round
 * icons), 2 shrinks it to an Old School pixel sprite with a black outline.
 */
class IconCache(
    private val source: AppSource,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val post: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val onLoaded: () -> Unit,
) {
    var style = 1
        private set
    private var rim = 0xFFB08A48.toInt()

    private val cache = HashMap<String, Bitmap>()
    /** Icons being refreshed after an update: shown until the new one is ready. */
    private val stale = HashMap<String, Bitmap>()
    private val pending = HashSet<String>()
    private var notifyQueued = false

    /** Switches look. Old bitmaps are dropped so two full sets never sit in memory. */
    fun configure(style: Int, rimColor: Int) {
        if (style == this.style && rimColor == rim) return
        this.style = style
        this.rim = rimColor
        cache.clear()
        onLoaded()
    }

    private fun keyOf(app: AppEntry) = "${app.key}@$style${if (style == 1) Integer.toHexString(rim) else ""}"

    fun get(app: AppEntry): Bitmap? {
        val k = keyOf(app)
        cache[k]?.let { return it }
        if (pending.add(k)) {
            val s = style
            val r = rim
            executor.execute {
                val bmp = try {
                    stylize(source.icon(app), s, r)
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

        fun stylize(d: Drawable?, style: Int, rim: Int): Bitmap = when (style) {
            0 -> render(d, SIZE)
            2 -> pixelate(render(d, 144), 28)
            else -> gilded(render(d, SIZE, SIZE / 11), rim)
        }

        /** The icon on a dark disc inside a two-tone metal rim, with a soft drop shadow. */
        fun gilded(base: Bitmap, rim: Int): Bitmap {
            val n = base.width
            val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val cx = n / 2f
            val r = n / 2f - n / 40f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.setShadowLayer(n / 28f, 0f, n / 48f, 0x90000000.toInt())
            p.color = 0xFF0B1014.toInt()
            c.drawCircle(cx, cx, r, p)
            p.clearShadowLayer()
            c.drawBitmap(base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            p.style = Paint.Style.STROKE
            p.strokeWidth = n / 18f
            p.shader = LinearGradient(0f, 0f, 0f, n.toFloat(), mix(rim, 0xFFFFFFFF.toInt(), 0.45f), mix(rim, 0xFF000000.toInt(), 0.45f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cx, r - p.strokeWidth / 2f, p)
            p.shader = null
            p.strokeWidth = n / 90f
            p.color = 0x99000000.toInt()
            c.drawCircle(cx, cx, r - n / 18f, p)
            return out
        }

        fun pixelate(src: Bitmap, n: Int): Bitmap {
            var b = src
            while (b.width / 2 >= n * 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            val small = Bitmap.createScaledBitmap(b, n, n, true)
            val pix = IntArray(n * n)
            small.getPixels(pix, 0, n, 0, 0, n, n)
            val w = n + 2
            val out = IntArray(w * w)
            for (y in 0 until n) for (x in 0 until n) {
                val col = pix[y * n + x]
                if ((col ushr 24) >= 110) out[(y + 1) * w + x + 1] = (col and 0x00F8F8F8) or 0xFF000000.toInt()
            }
            val res = out.copyOf()
            for (y in 0 until w) for (x in 0 until w) {
                if (out[y * w + x] != 0) continue
                val edge = (x > 0 && out[y * w + x - 1] != 0) || (x < w - 1 && out[y * w + x + 1] != 0) ||
                    (y > 0 && out[(y - 1) * w + x] != 0) || (y < w - 1 && out[(y + 1) * w + x] != 0)
                if (edge) res[y * w + x] = 0xFF0B0B0B.toInt()
            }
            // Scale up with hard edges so it stays crunchy at any size.
            val sprite = Bitmap.createBitmap(res, w, w, Bitmap.Config.ARGB_8888)
            return Bitmap.createScaledBitmap(sprite, w * 4, w * 4, false)
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
