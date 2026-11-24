package com.life0423.carddock.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 配对窗口离开时会失效。系统通知收取用户手输的端口和码，不读取系统窗口或剪贴板。 */
class PairingReply : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < 34 || intent.action != ACTION) return
        val input = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(INPUT)?.toString().orEmpty()
        val answer = PairingAnswer.parse(input)
        if (answer == null) {
            show(context, "请输入系统弹窗的六位配对码。手动指定时填写「配对端口 六位码」。")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val paired = PhoneLink.pair(app, answer.port, answer.code)
                if (paired) finishNotice(app) else show(app, PhoneLink.message.value.ifBlank { "请使用系统弹窗的六位配对码重试。" })
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val CHANNEL = "local_phone_pairing"
        private const val ACTION = "com.life0423.carddock.LOCAL_PAIR"
        private const val INPUT = "system_reply"
        private const val ID = 8731

        fun notificationsEnabled(c: Context): Boolean {
            val nm = c.getSystemService(NotificationManager::class.java)
            return nm.areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun show(c: Context, hint: String = "打开系统配对窗口，然后在这里回复六位配对码。"): Boolean {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "本机配对", NotificationManager.IMPORTANCE_DEFAULT))
            if (!notificationsEnabled(c)) return false
            val reply = PendingIntent.getBroadcast(c, ID, Intent(c, PairingReply::class.java).setAction(ACTION),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
            val input = RemoteInput.Builder(INPUT).setLabel("六位配对码").build()
            val action = Notification.Action.Builder(null, "填写配对信息", reply).addRemoteInput(input).build()
            nm.notify(ID, Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("卡坞 · 本机配对").setContentText(hint).setStyle(Notification.BigTextStyle().bigText(hint))
                .setVisibility(Notification.VISIBILITY_SECRET).setOnlyAlertOnce(true).setTimeoutAfter(180000)
                .setContentIntent(openApp(c)).addAction(action).build())
            return true
        }

        private fun openApp(c: Context) = PendingIntent.getActivity(c, ID,
            Intent(c, PhoneSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun cancel(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(ID)

        private fun finishNotice(c: Context) {
            c.getSystemService(NotificationManager::class.java).notify(ID, Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("卡坞已完成本机配对")
                .setContentText("点此返回卡坞，助手会自动连接。").setContentIntent(openApp(c))
                .setAutoCancel(true).setTimeoutAfter(180000).build())
        }
    }
}

