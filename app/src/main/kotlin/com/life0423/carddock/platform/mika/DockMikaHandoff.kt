package com.life0423.carddock.platform.mika

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 接收本 APK 的 shell 助手交接。进程令牌失效时，助手释放虚拟屏并退出。 */
class DockMikaHandoff : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "attach" || Binder.getCallingUid() != SHELL_UID) return null
        val binder = extras?.getBinder("display") ?: return null
        synchronized(token) {
            if (mutableDisplay.value?.asBinder()?.isBinderAlive == true) return null
            binder.linkToDeath({
                synchronized(token) {
                    if (mutableDisplay.value?.asBinder() == binder) mutableDisplay.value = null
                }
            }, 0)
            mutableDisplay.value = ICardDockDisplay.Stub.asInterface(binder)
        }
        return Bundle().apply { putBinder("app", token) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        internal const val SHELL_UID = 2000
        private val token = Binder()
        private val mutableDisplay = MutableStateFlow<ICardDockDisplay?>(null)
        internal val display = mutableDisplay.asStateFlow()
    }
}
