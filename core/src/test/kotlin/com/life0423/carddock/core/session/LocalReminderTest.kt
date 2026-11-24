package com.life0423.carddock.core.session

import com.life0423.carddock.core.rule.*
import java.time.ZonedDateTime
import org.junit.Test
import org.junit.Assert.*

class LocalReminderTest {
    private val now = ZonedDateTime.parse("2026-11-24T09:00:00+08:00[Asia/Shanghai]")
    @Test fun dailyAndOnceUseTheChosenLocalTime() {
        val daily = requireNotNull(parseLocalReminder("每日 08:20 提醒我出门",now))
        assertEquals("2026-11-25",daily.next(now)?.toLocalDate().toString())
        val once = requireNotNull(parseLocalReminder("一次 08:20 提醒我出门",now))
        assertEquals("2026-11-25",once.date)
        assertNull(once.next(now.plusDays(2)))
        for (text in listOf("每日 09:30 提醒我喝水", "每天早上九点半提醒我喝水", "请每天上午9点30分提醒喝水"))
            assertEquals("09:30", parseLocalReminder(text,now)?.clock)
        assertNull(parseLocalReminder("每周 09:01 提醒我出门",now))
        assertNull(parseLocalReminder("每天 24:61 提醒我出门",now))
        assertNull(parseLocalReminder("2026-02-31 09:00 提醒我出门",now))
    }
}
