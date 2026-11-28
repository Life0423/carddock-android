package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.uistate.ScreenSnapshot

/** 同一次读屏内的列表计数。每个复合行须同时包含能独立读到的标题和分组字段。 */
object ListQuantityCheck {
    data class Result(val answer: String, val checked: Int, val corrected: Int, val evidence: String)

    private val count = Regex("""(?<![\d.第前后每])([0-9]+)((?:\*{1,2}|__)?\s*(?:项|条|件|笔|个|份|行|单))""")
    private val total = Regex("""列表共|列表.*?合计|总计|总共|合计|一共|本[页屏](?:当前)?共|共计|共列出|共列了""")
    private val separators = setOf('。', '，', '、', ',', '；', ';', '\n', '：', ':')
    private val limited = Regex("""其中部分|前\s*$|最多\s*$|至少\s*$|超过\s*$|每页\s*$|展示前\s*$""")

    fun groups(screen: ScreenSnapshot?): Map<String, List<String>> {
        if (screen == null || screen.protected || !screen.hasWindow || screen.changedDuringRead ||
            screen.captureError != null) return emptyMap()
        val nodes = screen.elements.filter { !it.secret && !it.editable && !it.hint && it.bounds.size == 4 && it.name.isNotBlank() }
        val fields = nodes.groupBy { it.name.trim() }
        // 父容器和子节点可能重复描述同一行。同名条目在不同位置仍各算一项。
        val rows = linkedMapOf<Pair<String, List<Int>>, String>()
        for (row in nodes) {
            val text = row.name.trim()
            fun child(label: String) = fields[label]?.firstOrNull { e ->
                e !== row && e.bounds[0] >= row.bounds[0] && e.bounds[1] >= row.bounds[1] &&
                    e.bounds[2] <= row.bounds[2] && e.bounds[3] <= row.bounds[3]
            }
            for (space in text.indices.filter { text[it].isWhitespace() }.asReversed()) {
                val name = text.take(space)
                val title = child(name) ?: continue
                val rest = text.drop(space).trimStart()
                val group = fields.keys.filter { rest == it || rest.startsWith("$it ") }
                    .filter { child(it) != null && it != name }.maxByOrNull { it.length } ?: continue
                rows[name to title.bounds] = group
                break
            }
        }
        if (rows.size < 2) return emptyMap()
        return rows.entries.groupBy({ it.value }, { it.key.first })
    }

    fun check(answer: String, screen: ScreenSnapshot?): Result? {
        if (!count.containsMatchIn(answer)) return null
        val groups = groups(screen)
        if (groups.isEmpty()) return null
        val allItems = groups.values.flatten()
        val status = groups.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }.toRegex()
        var checked = 0
        var corrected = 0
        val edited = count.replace(answer) { match ->
            val start = match.range.first
            val prefix = answer.take(start).takeLastWhile { it !in separators }
            val suffix = answer.drop(match.range.last + 1).takeWhile { it !in separators }
            val clause = prefix + match.value + suffix
            if (limited.containsMatchIn(prefix) || listOf("上一页", "跨页", "上一次", "上次", "预计").any { it in clause }) return@replace match.value
            val labels = status.findAll(clause).map { it.value }.distinct().toList()
            val members = when {
                labels.size == 1 -> groups.getValue(labels.single())
                labels.isEmpty() && total.containsMatchIn(clause) -> allItems
                else -> return@replace match.value
            }
            // 只更正这份答复实际列举、且本屏逐项印证的集合。分页估计或仅列举几个样例不能当全量。
            if (!members.all { it in answer } || members.distinct().size != members.size) return@replace match.value
            val reported = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val sentence = answer.take(start).takeLastWhile { it != '。' && it != '\n' } + clause
            if (reported > members.size && "本页" !in sentence && "本屏" !in sentence &&
                !members.all { it in answer.drop(match.range.last + 1).substringBefore("\n\n") }) return@replace match.value
            checked++
            if (reported != members.size) corrected++
            members.size.toString() + match.groupValues[2]
        }
        if (checked == 0) return null
        val evidence = "本屏可逐项核实 " + allItems.size + " 项；" + groups.entries.joinToString("，") { (label, items) -> "$label ${items.size} 项" }
        return Result(edited, checked, corrected, evidence)
    }
}
