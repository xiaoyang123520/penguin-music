package android.graphics

import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Desktop Bitmap backed by BufferedImage. */
class Bitmap(val image: BufferedImage) {
    val width: Int get() = image.width
    val height: Int get() = image.height
    val isRecycled: Boolean get() = false
    val config: Config get() = Config.ARGB_8888

    fun getPixel(x: Int, y: Int): Int = image.getRGB(x, y)

    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        var i = offset
        for (row in 0 until height) {
            var j = i
            for (col in 0 until width) {
                pixels[j] = image.getRGB(x + col, y + row)
                j++
            }
            i += stride
        }
    }

    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        val fmt = when (format) {
            CompressFormat.PNG -> "png"
            else -> "jpg"
        }
        val src = if (fmt == "jpg" && image.type != BufferedImage.TYPE_INT_RGB) {
            val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = rgb.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            rgb
        } else image
        return runCatching { ImageIO.write(src, fmt, stream) }.getOrDefault(false)
    }

    fun recycle() {}

    fun copy(config: Config, isMutable: Boolean): Bitmap {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return Bitmap(out)
    }

    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, HARDWARE }

    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap =
            Bitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))

        @JvmStatic
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap =
            Bitmap(source.image.getSubimage(x, y, width, height))

        @JvmStatic
        fun createBitmap(
            source: Bitmap, x: Int, y: Int, width: Int, height: Int,
            m: Matrix?, filter: Boolean
        ): Bitmap = createBitmap(source, x, y, width, height)

        @JvmStatic
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
            val out = BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            if (filter) g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.drawImage(src.image, 0, 0, dstWidth, dstHeight, null)
            g.dispose()
            return Bitmap(out)
        }
    }
}

object BitmapFactory {
    class Options {
        var inJustDecodeBounds: Boolean = false
        var inSampleSize: Int = 1
        var inMutable: Boolean = false
        var inPreferredConfig: Bitmap.Config? = null
        var inDensity: Int = 0
        var inTargetDensity: Int = 0
        var outWidth: Int = -1
        var outHeight: Int = -1
        var outMimeType: String? = null
    }

    @JvmStatic
    fun decodeStream(input: InputStream?, outPadding: Rect?, opts: Options?): Bitmap? =
        decodeStreamImpl(input, outPadding, opts ?: Options())

    private fun decodeStreamImpl(input: InputStream?, outPadding: Rect?, opts: Options): Bitmap? {
        if (input == null) return null
        val img = runCatching { ImageIO.read(input) }.getOrNull() ?: return null
        opts.outWidth = img.width
        opts.outHeight = img.height
        if (opts.inJustDecodeBounds) return null
        val sample = opts.inSampleSize.coerceAtLeast(1)
        if (sample == 1) return Bitmap(img)
        val w = (img.width / sample).coerceAtLeast(1)
        val h = (img.height / sample).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(Bitmap(img), w, h, true)
    }

    @JvmStatic
    fun decodeStream(input: InputStream?): Bitmap? = decodeStream(input, null, Options())

    @JvmStatic
    fun decodeFile(pathName: String?, opts: Options?): Bitmap? =
        runCatching { java.io.File(pathName ?: return null).inputStream().use { decodeStream(it, null, opts ?: Options()) } }.getOrNull()

    @JvmStatic
    fun decodeFile(pathName: String?): Bitmap? = decodeFile(pathName, Options())

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? {
        val img = runCatching { ImageIO.read(data.inputStream(offset, length)) }.getOrNull() ?: return null
        (opts ?: Options()).apply { outWidth = img.width; outHeight = img.height }
        if (opts?.inJustDecodeBounds == true) return null
        return Bitmap(img)
    }

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? =
        decodeByteArray(data, offset, length, Options())
}

class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun set(l: Int, t: Int, r: Int, b: Int) { left = l; top = t; right = r; bottom = b }
}

class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    constructor(r: Rect) : this(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
}

class Matrix {
    fun setScale(sx: Float, sy: Float) {}
    fun postScale(sx: Float, sy: Float): Boolean = true
    fun postTranslate(dx: Float, dy: Float): Boolean = true
    fun reset() {}
}

class PorterDuffXfermode(val mode: PorterDuff.Mode)

object PorterDuff {
    enum class Mode { CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT, SRC_ATOP, DST_ATOP, XOR }
}

class Paint {
    constructor()

    constructor(flags: Int) {
        if (flags and ANTI_ALIAS_FLAG != 0) isAntiAlias = true
        if (flags and FILTER_BITMAP_FLAG != 0) isFilterBitmap = true
        if (flags and DITHER_FLAG != 0) isDither = true
    }

    constructor(paint: Paint) : this() {
        color = paint.color
        alpha = paint.alpha
        isAntiAlias = paint.isAntiAlias
        isFilterBitmap = paint.isFilterBitmap
        isDither = paint.isDither
        style = paint.style
        strokeWidth = paint.strokeWidth
        textSize = paint.textSize
        xfermode = paint.xfermode
    }

    var color: Int = Color.BLACK
    var alpha: Int = 255
    var isAntiAlias: Boolean = false
    var isFilterBitmap: Boolean = false
    var isDither: Boolean = false
    var style: Style = Style.FILL
    var strokeWidth: Float = 0f
    var textSize: Float = 12f
    var xfermode: PorterDuffXfermode? = null

    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Align { LEFT, CENTER, RIGHT }

    companion object {
        const val ANTI_ALIAS_FLAG = 0x01
        const val FILTER_BITMAP_FLAG = 0x02
        const val DITHER_FLAG = 0x04
        const val UNDERLINE_TEXT_FLAG = 0x08
        const val STRIKE_THRU_TEXT_FLAG = 0x10
        const val FAKE_BOLD_TEXT_FLAG = 0x20
        const val LINEAR_TEXT_FLAG = 0x40
        const val SUBPIXEL_TEXT_FLAG = 0x80
        const val DEV_KERN_TEXT_FLAG = 0x100
        const val HINTING_OFF = 0
        const val HINTING_ON = 1
    }
}

class Canvas(private val bitmap: Bitmap) {
    private val g: Graphics2D = bitmap.image.createGraphics()

    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    }

    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        applyPaint(paint)
        g.drawImage(bitmap.image, left.roundToInt(), top.roundToInt(), null)
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        applyPaint(paint)
        val sx = src?.left ?: 0
        val sy = src?.top ?: 0
        val sw = src?.width() ?: bitmap.width
        val sh = src?.height() ?: bitmap.height
        g.drawImage(
            bitmap.image,
            dst.left.roundToInt(), dst.top.roundToInt(),
            dst.right.roundToInt(), dst.bottom.roundToInt(),
            sx, sy, sx + sw, sy + sh, null
        )
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) =
        drawBitmap(bitmap, src, RectF(dst), paint)

    fun drawColor(color: Int) {
        val old = g.color
        g.color = java.awt.Color(color, true)
        g.fillRect(0, 0, bitmap.width, bitmap.height)
        g.color = old
    }

    fun drawRect(rect: RectF, paint: Paint) {
        applyPaint(paint)
        g.fillRect(
            rect.left.roundToInt(), rect.top.roundToInt(),
            rect.width().roundToInt(), rect.height().roundToInt()
        )
    }

    private fun applyPaint(paint: Paint?) {
        if (paint == null) return
        g.color = java.awt.Color(paint.color, true)
        if (paint.alpha != 255) {
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, paint.alpha / 255f)
        }
        when (paint.xfermode?.mode) {
            PorterDuff.Mode.SRC_IN ->
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_IN)
            PorterDuff.Mode.SRC ->
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC)
            else -> Unit
        }
    }
}

object Color {
    const val TRANSPARENT: Int = 0
    const val BLACK: Int = -0x1000000
    const val WHITE: Int = -0x1
    const val RED: Int = -0xff0000
    const val GREEN: Int = -0xff00
    const val BLUE: Int = -0xffff
    const val GRAY: Int = -0x777778
    const val LTGRAY: Int = -0x333334
    const val DKGRAY: Int = -0xbbbbbc
    const val YELLOW: Int = -0x100
    const val CYAN: Int = -0xff0001
    const val MAGENTA: Int = -0xff01

    @JvmStatic fun alpha(color: Int): Int = color ushr 24
    @JvmStatic fun red(color: Int): Int = (color shr 16) and 0xFF
    @JvmStatic fun green(color: Int): Int = (color shr 8) and 0xFF
    @JvmStatic fun blue(color: Int): Int = color and 0xFF

    @JvmStatic
    fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    @JvmStatic
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    @JvmStatic
    fun colorToHSV(color: Int, hsv: FloatArray) {
        val c = java.awt.Color(red(color), green(color), blue(color))
        val vals = java.awt.Color.RGBtoHSB(c.red, c.green, c.blue, null)
        hsv[0] = vals[0] * 360f
        hsv[1] = vals[1]
        hsv[2] = vals[2]
    }

    @JvmStatic
    fun HSVToColor(hsv: FloatArray): Int {
        val c = java.awt.Color.getHSBColor(hsv[0] / 360f, hsv[1], hsv[2])
        return argb(0xFF, c.red, c.green, c.blue)
    }

    @JvmStatic
    fun HSVToColor(alpha: Int, hsv: FloatArray): Int {
        val c = java.awt.Color.getHSBColor(hsv[0] / 360f, hsv[1], hsv[2])
        return argb(alpha, c.red, c.green, c.blue)
    }

    @JvmStatic
    fun parseColor(colorString: String): Int {
        val awt = java.awt.Color.decode(
            if (colorString.startsWith("#")) colorString else "#$colorString"
        )
        return argb(awt.alpha, awt.red, awt.green, awt.blue)
    }
}
