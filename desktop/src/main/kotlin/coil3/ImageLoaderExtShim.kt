@file:JvmName("ImageLoaderDesktopExtKt")

package coil3

import android.content.Context

/**
 * On Android, coil3 ships `val android.content.Context.imageLoader`. The JVM
 * artifact only provides it for PlatformContext; this extension restores the
 * same call shape for the app's android.content.Context shim.
 */
val Context.imageLoader: ImageLoader
    get() = SingletonImageLoader.get(PlatformContext.INSTANCE)
