package android.content

import android.net.Uri
import android.os.Bundle

/** 桌面垫片：只保存 action/data/extras，不真正跨进程。 */
open class Intent {
    var action: String? = null
    var data: Uri? = null
    var type: String? = null
    var flags: Int = 0
    var component: ComponentName? = null
    var `package`: String? = null
    val extras: Bundle = Bundle()

    constructor()

    constructor(action: String?) {
        this.action = action
    }

    constructor(action: String?, uri: Uri?) {
        this.action = action
        this.data = uri
    }

    constructor(context: Context, cls: Class<*>) {
        this.component = ComponentName(context.packageName, cls.name)
    }

    constructor(context: Context, className: String) {
        this.component = ComponentName(context.packageName, className)
    }

    fun setData(data: Uri?): Intent = apply { this.data = data }
    fun setType(type: String?): Intent = apply { this.type = type }
    fun setAction(action: String?): Intent = apply { this.action = action }
    fun addFlags(flags: Int): Intent = apply { this.flags = this.flags or flags }
    fun setFlags(flags: Int): Intent = apply { this.flags = flags }
    fun setPackage(pkg: String?): Intent = apply { `package` = pkg }
    fun setComponent(component: ComponentName?): Intent = apply { this.component = component }

    fun putExtra(name: String, value: String?): Intent = apply { extras.putString(name, value) }
    fun putExtra(name: String, value: Long): Intent = apply { extras.putLong(name, value) }
    fun putExtra(name: String, value: Int): Intent = apply { extras.putInt(name, value) }
    fun putExtra(name: String, value: Boolean): Intent = apply { extras.putBoolean(name, value) }
    fun putExtra(name: String, value: Uri?): Intent = apply { extras.putParcelable(name, value) }
    fun putExtra(name: String, value: Bundle?): Intent = apply { extras.putBundle(name, value) }

    fun getStringExtra(name: String): String? = extras.getString(name)
    fun getLongExtra(name: String, defaultValue: Long): Long = extras.getLong(name, defaultValue)
    fun getIntExtra(name: String, defaultValue: Int): Int = extras.getInt(name, defaultValue)
    fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean =
        extras.getBoolean(name, defaultValue)

    fun hasExtra(name: String): Boolean = extras.containsKey(name)

    companion object {
        const val ACTION_VIEW: String = "android.intent.action.VIEW"
        const val ACTION_SEND: String = "android.intent.action.SEND"
        const val ACTION_SEND_MULTIPLE: String = "android.intent.action.SEND_MULTIPLE"
        const val ACTION_CHOOSER: String = "android.intent.action.CHOOSER"
        const val ACTION_MAIN: String = "android.intent.action.MAIN"
        const val ACTION_PICK: String = "android.intent.action.PICK"
        const val ACTION_GET_CONTENT: String = "android.intent.action.GET_CONTENT"
        const val ACTION_OPEN_DOCUMENT: String = "android.intent.action.OPEN_DOCUMENT"
        const val ACTION_CREATE_DOCUMENT: String = "android.intent.action.CREATE_DOCUMENT"
        const val ACTION_INSTALL_PACKAGE: String = "android.content.pm.action.REQUEST_PERMISSIONS"
        const val CATEGORY_DEFAULT: String = "android.intent.category.DEFAULT"
        const val CATEGORY_BROWSABLE: String = "android.intent.category.BROWSABLE"

        const val EXTRA_TEXT: String = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT: String = "android.intent.extra.SUBJECT"
        const val EXTRA_STREAM: String = "android.intent.extra.STREAM"
        const val EXTRA_TITLE: String = "android.intent.extra.TITLE"
        const val EXTRA_INTENT: String = "android.intent.extra.INTENT"

        const val FLAG_ACTIVITY_NEW_TASK: Int = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TOP: Int = 0x04000000
        const val FLAG_GRANT_READ_URI_PERMISSION: Int = 0x00000001
        const val FLAG_GRANT_WRITE_URI_PERMISSION: Int = 0x00000002

        @JvmStatic
        fun createChooser(target: Intent?, title: CharSequence?): Intent =
            Intent(ACTION_CHOOSER).apply {
                if (target != null) {
                    action = target.action
                    data = target.data
                    type = target.type
                    extras.putAll(target.extras)
                }
            }
    }
}

class ComponentName {
    val packageName: String
    val className: String

    constructor(pkg: String, cls: String) {
        packageName = pkg
        className = cls
    }

    constructor(context: Context, cls: Class<*>) {
        packageName = context.packageName
        className = cls.name
    }

    fun flattenToString(): String = "$packageName/$className"
}
