package com.life0423.carddock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.life0423.carddock.R
import com.life0423.carddock.core.model.*
import com.life0423.carddock.core.rule.RuleSpec
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 主题只控制图形，旧规则按来源推断，不按可修改的卡名猜测。 */
internal fun inferredTheme(kind: TemplateKind, rule: RuleSpec?) = when {
    rule?.sourceId == "com.mcdonalds.gma.cn" -> CardTheme.FOOD
    rule?.countRoute != null -> CardTheme.FILE
    rule?.reminderRoute != null -> CardTheme.CALENDAR
    rule?.sourceId == "apple.cn.display" -> CardTheme.PRICE
    rule?.sourceId == "com.sankuai.meituan" -> CardTheme.ORDER
    rule?.sourceId == "com.jingdong.app.mall" -> CardTheme.DIGITAL
    else -> when(kind) {
        TemplateKind.EXPRESS -> CardTheme.PARCEL
        TemplateKind.SHOPPING -> CardTheme.SHOPPING
        TemplateKind.DELIVERY -> CardTheme.FOOD
        TemplateKind.REMINDER -> CardTheme.REMINDER
        TemplateKind.TRAVEL -> CardTheme.TRAVEL
        TemplateKind.QUERY -> CardTheme.OTHER
    }
}

private val CardTheme.drawable get() = when(this) {
    CardTheme.FOOD -> R.drawable.ticket_meal
    CardTheme.PARCEL -> R.drawable.ticket_parcel
    CardTheme.ORDER -> R.drawable.theme_order
    CardTheme.SHOPPING -> R.drawable.theme_shopping
    CardTheme.DIGITAL -> R.drawable.ticket_shopping
    CardTheme.PRICE -> R.drawable.theme_price
    CardTheme.BILL -> R.drawable.theme_bill
    CardTheme.FILE -> R.drawable.ticket_files
    CardTheme.CALENDAR -> R.drawable.theme_calendar
    CardTheme.REMINDER -> R.drawable.theme_bell
    CardTheme.TRAVEL -> R.drawable.theme_travel
    CardTheme.TICKET -> R.drawable.theme_ticket
    CardTheme.STUDY -> R.drawable.theme_study
    CardTheme.WORK -> R.drawable.theme_work
    CardTheme.SPORT -> R.drawable.theme_sport
    CardTheme.OTHER -> R.drawable.theme_other
}

@Composable internal fun KindIcon(kind: TemplateKind, rule: RuleSpec? = null, modifier: Modifier = Modifier.size(42.dp), theme: CardTheme? = null) {
    Image(painterResource((theme ?: inferredTheme(kind, rule)).drawable), null, modifier)
}

/** 取消不改变当前主题，确认后模板、预览和空间复用同一矢量资源。 */
@Composable internal fun ThemePicker(t: TemplateDefinition, enabled: Boolean, save: (CardTheme?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = t.theme ?: inferredTheme(t.kind,t.rule)
    TextButton({ open = true },enabled = enabled) { Text("卡片图标 · ${current.label}") }
    if (open) {
        var pick by rememberSaveable { mutableStateOf(current) }
        androidx.compose.ui.window.Dialog(onDismissRequest={open=false},
            properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(modifier=Modifier.fillMaxWidth().fillMaxHeight(0.90f),color=com.life0423.carddock.ui.theme.DockColors.Background) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
                    verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton({open=false}) { Text("返回") }
                        Text("选择卡片图标",style=MaterialTheme.typography.headlineSmall)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        CardTheme.entries.chunked(4).forEach { row ->
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { row.forEach { icon ->
                                OutlinedButton({pick=icon},modifier=Modifier.weight(1f),contentPadding=PaddingValues(vertical=8.dp),
                                    shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                                    Column(horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally) {
                                        Image(painterResource(icon.drawable),null,Modifier.size(42.dp))
                                        Text((if(pick==icon) "✓" else "")+icon.label,style=MaterialTheme.typography.labelLarge)
                                    }
                                }
                            } }
                        }
                    }
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Image(painterResource(pick.drawable),null,Modifier.size(48.dp));Text(t.title,style=MaterialTheme.typography.titleLarge)
                    }
                    Button({save(pick);open=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("使用这个图标")}
                }
            }
        }
    }
}

/** 一级只展示能力；点击才列出该类实际接入的例子，保留自由输入。 */
@Composable internal fun CapabilityBrowser(busy: Boolean, templates: List<TemplateDefinition>, choose: (TemplateDefinition)->Unit, fill:(String)->Unit, pickApp: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf("") }
    val groups = listOf("一键直达","查询状态","提取与统计","比较与推荐","变化提醒","日程与提醒","自动化操作")
    OutlinedButton({ expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy, shape = DockButtonShape) { Text(if (expanded) "收起支持的能力" else "浏览支持的能力") }
    if (!expanded) return
    groups.chunked(2).forEach { pair -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        pair.forEach { label -> OutlinedButton({group = label},enabled = !busy,modifier = Modifier.weight(1f).heightIn(min=48.dp)) {
            val art = when(label) {
                "自动化操作" -> R.drawable.theme_shopping
                "一键直达" -> R.drawable.theme_ticket
                "查询状态" -> R.drawable.ticket_templates_active
                "提取与统计" -> R.drawable.theme_bill
                "比较与推荐" -> R.drawable.ticket_shopping
                "变化提醒" -> R.drawable.theme_bell
                else -> R.drawable.theme_calendar
            }
            Image(painterResource(art),null,Modifier.size(22.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (group == label) "✓ $label" else label)
        } }
    } }
    if (group == "自动化操作") TextButton(onClick = pickApp, enabled = !busy) { Text("打开手机上的应用") }
    if (group == "自动化操作" || group == "比较与推荐") TextButton({
        choose(com.life0423.carddock.BookSearch("悬疑小说", 2).template(com.life0423.carddock.BOOK_PROMPT))
        expanded = false
    }, enabled = !busy) { Text("淘宝找书") }
    val time = LocalTime.now().plusMinutes(4).format(DateTimeFormatter.ofPattern("HH:mm"))
    val examples = when(group) {
        "一键直达" -> listOf("官方待取列表" to "打开菜鸟待取列表","取件亮码" to "查快递，到驿站时出示取件身份码","麦当劳选套餐" to "打开麦当劳选择「炸鸡随心配1+1」")
        "自动化操作" -> listOf("确认后加购一份套餐" to com.life0423.carddock.ai.CART_MENU_PROMPT, "打开官方待取列表" to "打开菜鸟待取列表")
        "查询状态" -> listOf("探索套餐分组" to "探索麦当劳「炸鸡随心配1+1」的分组", "菜鸟待取状态" to "查看菜鸟待取包裹","美团最近一单" to "查看美团最近一单外卖状态","即时菜单规格" to "查看麦当劳「灰焰圆筒」的规格", "核对固定搭配" to "核对麦当劳「炸鸡随心配1+1」的固定搭配")
        "提取与统计" -> listOf("文件分类数量" to "查看文件管理的文件页中安装包的数量","已完成订单实付" to "查看美团历史订单的合计")
        "比较与推荐" -> listOf("办公显示器比较" to "比较海信 GX 和华硕 ROG，主要用于编程办公","官网价格榜" to "查看苹果官网显示器价格榜")
        "变化提醒" -> listOf("官网降价提醒" to "每天跟踪苹果官网显示器价格，降价提醒我")
        "日程与提醒" -> listOf("读取下一条日程" to "查看下一个提醒的名称和时间","一次提醒" to "一次 $time 提醒我休息","每日提醒" to "每天 09:00 提醒我查看日程")
        else -> emptyList()
    }
    examples.forEach { (label,demand) -> TextButton({fill(demand);expanded=false}, enabled=!busy) { Text(label) } }
    if(group == "提取与统计") templates.filter { it.requiresOrderNumber }.forEach { t ->
        TextButton({choose(t);expanded=false},enabled=!busy){Text(t.title)}
    }
}
