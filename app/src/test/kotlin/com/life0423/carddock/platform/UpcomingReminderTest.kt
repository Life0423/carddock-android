package com.life0423.carddock.platform

import android.graphics.Rect
import com.life0423.carddock.core.port.DeviceResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** 日期形标题和不能归属的时间都曾导致分组边界判断错误。直接把页面交给实际读取器。 */
@RunWith(RobolectricTestRunner::class)
class UpcomingReminderTest {
    private fun list(withOrphan: Boolean): DetailWindow {
        val date = LocalDate.now()
        fun text(label: String, top: Int) = DetailNode(-1, null, "android.widget.TextView", label, true, Rect(4,top,80,top+8))
        val nodes = buildList {
            add(text("${date.monthValue}/${date.dayOfMonth}", 10))
            add(DetailNode(-1,null,"android.view.ViewGroup","日程,11/20,20:00",true,Rect(0,30,100,80)))
            add(text("11/20",35))
            add(text("20:00",50))
            if (withOrphan) add(text("18:00",85))
            add(text("01/01",100))
        }
        return DetailWindow("source", nodes, false)
    }

    @Test fun dateLikeTitleIsOpenedAsAnEvent() = runTest {
        var clicked: String? = null
        UpcomingReminder("source",null,null,{}).readOne({ clicked == null }, { list(false) },
            { clicked = it.label; true })
        assertEquals("11/20", clicked)
    }

    @Test fun unassignedVisibleTimePreventsSelection() = runTest {
        val result = UpcomingReminder("source",null,null,{}).readOne({ true }, { list(true) },
            { fail("不能跳过未识别的日程"); false })
        assertTrue((result as DeviceResponse.NeedsUser).message.contains("未能归入"))
    }
}
