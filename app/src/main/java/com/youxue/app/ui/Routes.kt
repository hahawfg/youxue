package com.youxue.app.ui

/**
 * 全应用路由表。
 *
 * 集中定义而不是散落在各处的字符串字面量，改路由名时编译器能帮忙查漏。
 * 后续加新功能（成语接龙、古诗词、生字词库等）都在这里登记。
 */
object Routes {
    const val HOME = "home"
    const val DICTATION_SETUP = "dictation/setup"
    const val DICTATION_RUNNING = "dictation/running"
    const val DICTATION_RESULT = "dictation/result"
    const val TASKS = "tasks"

    /** 拍照识字：框选区域 */
    const val OCR_CROP = "ocr/crop"

    /** 拍照识字：确认识别结果 */
    const val OCR_PREVIEW = "ocr/preview"

    /** 学习计划：列表 */
    const val PLANS = "plans"

    /** 学习计划：详情（打卡日历）。[planId] 是 UUID，短且不含斜杠，适合放路径段 */
    const val PLAN_DETAIL = "plan/detail"
    const val ARG_PLAN_ID = "planId"

    /**
     * 详情页的落地路由模板。
     *
     * 单独一个常量，是因为 NavHost 注册时要的是带 `{}` 的模板，
     * 而跳转时要的是填好的实际路径——两者都从这里派生，改名字时不会漏一处。
     */
    const val PLAN_DETAIL_ROUTE = "$PLAN_DETAIL/{$ARG_PLAN_ID}"

    fun planDetail(planId: String): String = "$PLAN_DETAIL/$planId"
}
