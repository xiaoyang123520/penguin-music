package android.content.res

import android.content.StringResources

/** 桌面垫片：仅 getString/getText 走 Strings 表。 */
object Resources {
    fun getString(resId: Int): String = StringResources.lookup(resId)
    fun getText(resId: Int): CharSequence = StringResources.lookup(resId)
    fun getIdentifier(name: String, defType: String, defPackage: String): Int = 0
}
