package android.content

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

class ClipData(val label: CharSequence?, val text: CharSequence?) {
    val itemCount: Int get() = 1

    fun getItemAt(index: Int): Item = Item(text)

    class Item(val text: CharSequence?) {
        fun coerceToText(context: Context): CharSequence = text ?: ""
    }

    companion object {
        @JvmStatic
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData =
            ClipData(label, text)
    }
}

class ClipDescription(val label: CharSequence?)

/** Desktop clipboard backed by the AWT system clipboard. */
class ClipboardManager {
    @Volatile
    private var lastClip: ClipData? = null

    fun setPrimaryClip(clip: ClipData) {
        lastClip = clip
        runCatching {
            val text = clip.text?.toString().orEmpty()
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    fun hasPrimaryClip(): Boolean = primaryClip != null

    val primaryClip: ClipData?
        get() {
            val awtText = runCatching {
                val cb = Toolkit.getDefaultToolkit().systemClipboard
                if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    cb.getData(DataFlavor.stringFlavor) as? String
                } else null
            }.getOrNull()
            return when {
                awtText != null -> ClipData(null, awtText)
                else -> lastClip
            }
        }
}
