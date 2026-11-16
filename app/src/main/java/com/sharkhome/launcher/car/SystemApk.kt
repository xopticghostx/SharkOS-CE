package com.sharkhome.launcher.car

import android.content.Context
import android.content.pm.ApplicationInfo
import dalvik.system.PathClassLoader
import java.io.File

/**
 * Loads classes from one of the head unit's factory apps, the way HomeSpace does.
 * Refuses unless the app is a system app installed in a read-only system partition,
 * so a look-alike app installed later can never get its code run here.
 */
object SystemApk {
    private val SYSTEM_DIRS = listOf("/system/", "/system_ext/", "/product/", "/vendor/")
    private val cache = HashMap<String, ClassLoader>()

    @Synchronized
    fun classLoader(ctx: Context, pkg: String): ClassLoader {
        cache[pkg]?.let { return it }
        val info = ctx.packageManager.getApplicationInfo(pkg, 0)
        val path = File(info.sourceDir).canonicalPath
        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && SYSTEM_DIRS.any { path.startsWith(it) }
        require(isSystem) { "$pkg is not a factory system app; refusing to load its code" }
        val loader = PathClassLoader(path, ctx.classLoader)
        cache[pkg] = loader
        return loader
    }
}
