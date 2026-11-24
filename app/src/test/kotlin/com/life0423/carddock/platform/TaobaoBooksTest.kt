package com.life0423.carddock.platform

import com.life0423.carddock.BookSearch
import com.life0423.carddock.TAOBAO
import com.life0423.carddock.isBookQuery
import com.life0423.carddock.nativeRule
import org.junit.Assert.*
import org.junit.Test

class TaobaoBooksTest {
    private fun node(parent: Int, id: String = "", label: String = "", role: String = "android.widget.FrameLayout") =
        DetailNode(parent, "$TAOBAO:id/$id", role, label, true)

    @Test fun verifiesMainTitleBeforeLookingForRequestedBook() {
        val title = "福尔摩斯探案全集 精装三册 中文版"
        val other = "另一部侦探悬疑小说 精装典藏版本"
        val product = listOf(node(-1, "rv_main_screen_container"), node(0, label = "¥"), node(0),
            node(2, "ll_dinamicx_with_time_container"), node(3, "ll_dinamicx_container"), node(4),
            node(5, label = title, role = "android.view.View"), node(0, "tt_detail_nav_bar_share_icon"),
            node(0), node(8, "ll_dinamicx_with_time_container"), node(9, "ll_dinamicx_container"), node(10),
            node(11, label = other, role = "android.view.View"))
        val page = DetailWindow(TAOBAO, product, false)
        assertEquals(title, bookDetail(page, title))
        assertNull(bookDetail(page, other))
        assertNull(bookDetail(page.copy(password = true), title))
    }

    @Test fun onlySingleOfficialProductIdCanProduceAReplayCandidate() {
        val page = "var url = 'https://pages-fast.m.taobao.com/wow/example?src=msdetail&itemIds=123456789';"
        assertEquals("https://item.taobao.com/item.htm?id=123456789", sharedItemUrl(page))
        assertNull(sharedItemUrl(page.replace("pages-fast.m.taobao.com", "example.com")))
        assertNull(sharedItemUrl(page.replace("123456789", "123456789,456789012")))
        assertNull(sharedItemUrl(page.replace("src=msdetail", "itemIds=345678901")))
    }

    @Test fun freeInputPreservesCountAndGenreAndRejectsAdditionalConstraints() {
        assertEquals(BookSearch("小说", 2), com.life0423.carddock.simpleBookSearch("帮我在淘宝里推荐一些好看的小说并给我相应的购买链接以及推荐理由"))
        assertEquals(BookSearch("科幻小说", 3), com.life0423.carddock.simpleBookSearch("淘宝找三本科幻小说"))
        assertNull(com.life0423.carddock.simpleBookSearch("在淘宝推荐六本悬疑小说"))
        assertNull(com.life0423.carddock.simpleBookSearch("在淘宝推荐两本科幻小说，预算30元"))
    }

    @Test fun offlineShelfKeepsDistinctBooksAndExactGroundsFromOfficialTitles() {
        val titles = listOf("乙一 正版小说 夏天烟火和我的尸体 精装收藏版 悬疑推理",
            "福尔摩斯探案全集原版原著中文版无删减 精装硬壳全套3本 柯南道尔侦探悬疑推理小说",
            "喜鹊谋杀案32开书 侦探悬疑推理外国文学小说")
        assertEquals(listOf("夏天烟火和我的尸体", "福尔摩斯探案全集", "喜鹊谋杀案"),
            chooseBooks("悬疑小说", titles, null).map { it.title })
    }

    @Test fun savedRouteRetainsParametersAndOtherTaobaoCardsKeepTheirRoute() {
        val search = BookSearch("中文版 科幻小说", 3)
        assertEquals(search, BookSearch.fromKey(search.template("找书").rule.targetKey))
        assertTrue(search.template("找书").rule.isBookQuery)
        assertFalse(nativeRule("数量", TAOBAO, COUNT_TARGET, "quantity" to "数量").isBookQuery)
        assertNull(BookSearch.fromKey("books/6/小说"))
    }
}
