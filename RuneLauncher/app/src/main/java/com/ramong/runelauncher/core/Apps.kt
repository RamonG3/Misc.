package com.ramong.runelauncher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
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

/** Where apps come from. The real one wraps [LauncherApps]; tests use a fake. */
interface AppSource {
    fun load(): List<AppEntry>
    fun icon(app: AppEntry): Drawable?
    fun launch(app: AppEntry, bounds: Rect?, options: Bundle?): Boolean
    fun details(app: AppEntry)
    fun uninstall(app: AppEntry)
    fun listen(onChanged: () -> Unit) {}
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
            // Nothing sensible to do; the caller already printed a chat message.
        }
    }

    override fun uninstall(app: AppEntry) {
        val i = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.user?.let { i.putExtra(Intent.EXTRA_USER, it) }
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            // Some devices block uninstalling system apps; the dialog just never appears.
        }
    }

    override fun listen(onChanged: () -> Unit) {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = onChanged()
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = onChanged()
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = onChanged()
            override fun onPackagesAvailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) = onChanged()
            override fun onPackagesUnavailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) = onChanged()
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
 * Loads app icons off the main thread and turns them into RuneScape-style item sprites:
 * shrunk to a small grid, hard-edged alpha, slightly posterised, with a 1px black outline.
 * [pixelSize] of 0 keeps the original high-resolution icon.
 */
class IconCache(
    private val source: AppSource,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val post: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val onLoaded: () -> Unit,
) {
    var pixelSize = 28
        set(v) {
            field = v
            onLoaded()
        }

    private val cache = HashMap<String, Bitmap>()
    private val pending = HashSet<String>()
    private var notifyQueued = false

    fun get(app: AppEntry): Bitmap? {
        val size = pixelSize
        val k = "${app.key}@$size"
        cache[k]?.let { return it }
        if (pending.add(k)) {
            executor.execute {
                val bmp = try {
                    val base = render(source.icon(app), 144)
                    if (size == 0) base else pixelate(base, size)
                } catch (e: Throwable) {
                    null
                }
                post(Runnable {
                    pending.remove(k)
                    if (bmp != null) cache[k] = bmp
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
        return cache[k]
    }

    fun invalidate(app: AppEntry) {
        cache.keys.removeAll { it.startsWith(app.key + "@") }
    }

    fun clear() = cache.clear()

    companion object {
        fun render(d: Drawable?, size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            if (d != null) {
                val c = Canvas(bmp)
                d.setBounds(0, 0, size, size)
                d.draw(c)
            }
            return bmp
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
                val c = pix[y * n + x]
                if ((c ushr 24) >= 110) out[(y + 1) * w + x + 1] = (c and 0x00F8F8F8) or 0xFF000000.toInt()
            }
            val res = out.copyOf()
            for (y in 0 until w) for (x in 0 until w) {
                if (out[y * w + x] != 0) continue
                val edge = (x > 0 && out[y * w + x - 1] != 0) || (x < w - 1 && out[y * w + x + 1] != 0) ||
                    (y > 0 && out[(y - 1) * w + x] != 0) || (y < w - 1 && out[(y + 1) * w + x] != 0)
                if (edge) res[y * w + x] = 0xFF0B0B0B.toInt()
            }
            return Bitmap.createBitmap(res, w, w, Bitmap.Config.ARGB_8888)
        }
    }
}
