package com.wangzan101.mika.workspace

/** Explicit, finite template matching; no model, free-form parameter extraction or implicit fallback. */
internal object MockCompiler {
    const val template = "查看菜鸟待取包裹"
    val accepted = setOf(template, "查询我的菜鸟待取状态", "查看菜鸟待取状态")
    fun supports(request: String): Boolean = request.trim().removeSuffix("。").removeSuffix("？") in accepted
}
