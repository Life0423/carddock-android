package com.wangzan101.mika.task

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.RunOrigin

/** 任务通知只携带本次运行的索引。会话可连续运行多次，不能用“最近一轮”反查。 */
data class TaskNotice(
    val threadId: String? = null,
    val runKey: String? = null,
    val recordId: String? = null,
    val taskId: String? = null,
    val routeId: String? = null,
) {
    internal constructor(run: TaskRun) : this(run.conversationId, run.key, run.runId.value,
        run.mcpExternalId?.takeIf { run.provenance.origin == RunOrigin.Ui },
        if (run.mcpExternalId != null && run.provenance.origin == RunOrigin.Ui) run.dockRoute?.id else null)

    /** 卡坞接管自己的通知入口；独立使用 Mika 时保留原应用导航。 */
    interface Host { fun taskNoticeIntent(notice: TaskNotice): Intent }

    /** 卡坞通知不复述模型或原屏正文。确认、回复和接管都在有完整上下文的详情中办理。 */
    internal fun dockNotification(context: Context, channel: String, title: String,
        ongoing: Boolean = false, quiet: Boolean = false): Notification? {
        if (context.applicationContext !is Host) return null
        val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText("点按查看任务详情").setContentIntent(pending(context))
            .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true)
        if (quiet) builder.alertQuietly()
        return builder.build()
    }

    internal companion object {
        fun status(state: TaskState) = when (state) {
            is TaskState.Held -> "任务已暂停"
            is TaskState.WaitingInput -> "任务需要补充信息"
            is TaskState.WaitingConfirmation -> "任务需要确认"
            is TaskState.WaitingTakeover -> "任务等待接管"
            is TaskState.Succeeded -> "任务已有答复"
            is TaskState.Failed -> "任务未完成"
            TaskState.Cancelled -> "任务已取消"
            else -> "任务正在执行"
        }
    }

    internal fun pending(context: Context): PendingIntent {
        val host = context.applicationContext as? Host ?: return TaskService.openApp(context, threadId)
        val target = host.taskNoticeIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        target.identifier = listOf(threadId, runKey, recordId, taskId).joinToString("/")
        return PendingIntent.getActivity(context, 0, target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
