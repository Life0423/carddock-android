package com.wangzan101.mika.experiment

internal const val CAINIAO_PACKAGE = "com.cainiao.wireless"

internal enum class CainiaoPage { OTHER_APP, AUTH, EMPTY, PICKUP, HOME, UNKNOWN }

/** Exact, visible labels from one application window; help text is not business evidence. */
internal fun classifyCainiaoPage(packageName: String?, labels: Set<String>, password: Boolean): CainiaoPage {
    if (packageName != CAINIAO_PACKAGE) return CainiaoPage.OTHER_APP
    if (password || "手机号登录" in labels || "登录/注册" in labels ||
        ("登录" in labels && labels.any { it.contains("手机号") || it.contains("验证码") })) {
        return CainiaoPage.AUTH
    }
    if ("取包裹" in labels && "暂无待取包裹" in labels) return CainiaoPage.EMPTY
    if ("取包裹" in labels && "首页" !in labels) return CainiaoPage.PICKUP
    if ("首页" in labels && "取包裹" in labels) return CainiaoPage.HOME
    return CainiaoPage.UNKNOWN
}
