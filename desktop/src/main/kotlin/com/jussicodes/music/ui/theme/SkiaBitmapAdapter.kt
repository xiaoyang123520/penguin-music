package com.jussicodes.music.ui.theme

import android.graphics.Bitmap
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Coil on JVM decodes images to Skia bitmaps, while the app's artwork seed
 * extraction works on android.graphics.Bitmap. Conversion goes through a
 * PNG encode/decode round-trip (bitmaps here are tiny sampled thumbnails).
 */
fun org.jetbrains.skia.Bitmap.toAndroidBitmap(): Bitmap {
    val fallback = Bitmap(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
    val encoded = runCatching {
        org.jetbrains.skia.Image.makeFromBitmap(this)
            .encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
            ?.bytes
    }.getOrNull() ?: return fallback
    val decoded = runCatching { ImageIO.read(encoded.inputStream()) }.getOrNull()
        ?: return fallback
    return Bitmap(decoded)
}
