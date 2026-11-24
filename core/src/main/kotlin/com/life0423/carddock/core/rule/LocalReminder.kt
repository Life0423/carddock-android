package com.life0423.carddock.core.rule

import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

/** 单次提醒固定本地日期，每日提醒按手机时区计算下一次。 */
@Serializable
data class LocalReminder(val subject: String, val hour: Int, val minute: Int, val date: String? = null, val enabled: Boolean = true) {
    val clock get() = LocalTime.of(hour,minute).format(DateTimeFormatter.ofPattern("HH:mm"))
    val label get() = (date ?: "每天") + " " + clock
    fun next(now: ZonedDateTime): ZonedDateTime? {
        val day = date?.let(LocalDate::parse) ?: now.toLocalDate()
        val at = day.atTime(hour,minute).atZone(now.zone)
        return when { at > now -> at; date == null -> at.plusDays(1); else -> null }
    }
}

/** 解析明确的时间与频率。单次未写日期时在理解需求时确定最近一次。 */
fun parseLocalReminder(text: String, now: ZonedDateTime): LocalReminder? {
    val m = Regex("""(?:请|帮我)?(一次|今天|明天|每天|每日|[0-9]{4}-[0-9]{2}-[0-9]{2})\s*(上午|早上|下午|晚上)?\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,3})(?:[:：]([0-5]\d)|[点时](?:(半)|([0-5]?\d)分?)?)\s*提醒(?:我)?\s*(.{1,80})""").matchEntire(text.trim()) ?: return null
    val (whenText, period, hourText, clockMinute, half, minuteText, subject) = m.destructured
    val hourNames = listOf("零","一","二","三","四","五","六","七","八","九","十","十一","十二")
    var hour = hourText.toIntOrNull() ?: hourNames.indexOf(hourText.replace("两","二"))
    if (hour !in 0..23 || period.isNotEmpty() && hour !in 1..12 || subject.isBlank()) return null
    if (period in setOf("下午","晚上") && hour < 12) hour += 12
    val minute = if (half.isNotEmpty()) 30 else (clockMinute.ifBlank { minuteText }).toIntOrNull() ?: 0
    val time = LocalTime.of(hour,minute)
    val today = now.toLocalDate()
    val day = when(whenText) {
        "每天","每日" -> null
        "明天" -> today.plusDays(1)
        "今天" -> today
        "一次" -> if (time > now.toLocalTime()) today else today.plusDays(1)
        else -> try { LocalDate.parse(whenText) } catch (_: DateTimeException) { return null }
    }
    return LocalReminder(subject.trim(),time.hour,time.minute,day?.toString())
}
