package com.youxue.app.dictation

/**
 * 词语解析与间隔计算工具。
 *
 * 这里刻意做成纯函数，不依赖任何 Android 类，方便单测覆盖。
 */
object WordParser {

    /** 单个词语的长度上限，防止用户误粘贴长句导致间隔计算失控 */
    const val MAX_WORD_LENGTH = 60

    /**
     * 把多行文本解析为词语列表。
     *
     * 规则：
     * - 一行一个词语
     * - 自动去除首尾空白（含全角空格、制表符）
     * - 跳过空行
     * - 去除行首的序号前缀，例如 `1. 苹果`、`1、苹果`、`(1) 苹果` 都会解析为 `苹果`
     * - 只有序号没有内容的行（如 `1.`）直接丢弃
     * - 超长行截断到 [MAX_WORD_LENGTH]
     * - 保持用户输入顺序
     */
    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim().trim('\u3000').trim() }
            .filter { it.isNotEmpty() }
            .map { stripIndexPrefix(it) }
            // 剥离序号后为空说明整行只有序号（如 "1."），丢弃
            .filter { it.isNotEmpty() }
            .map { if (it.length > MAX_WORD_LENGTH) it.substring(0, MAX_WORD_LENGTH) else it }
            .toList()
    }

    /**
     * 去掉行首的序号前缀，返回剩余内容。
     *
     * `1. 苹果` / `1、苹果` / `1)苹果` / `(1)苹果` / `一、苹果` → `苹果`
     * `1.`（只有序号）→ `""`，由调用方决定丢弃
     * 没有序号前缀 → 原样返回
     *
     * 公开出来是给 OCR 清洗复用——拍照识字同样会遇到课本题号，
     * 这条规则只该有一份实现。
     */
    fun stripIndexPrefix(line: String): String {
        val patterns = listOf(
            // (1) / （1） 可带分隔符
            Regex("""^[\(（]\s*\d+\s*[\)）]\s*[.、:：]?\s*"""),
            // 1. / 1、 / 1) / 1: 必须有分隔符，避免把 "2024年" 这类词误剥
            Regex("""^\d+\s*[.、:：\)）]\s*"""),
            // 一、 / 二. / 三)
            Regex("""^[一二三四五六七八九十百]+\s*[.、:：\)）]\s*"""),
        )
        for (p in patterns) {
            val m = p.find(line) ?: continue
            return line.substring(m.value.length).trim()
        }
        return line
    }

    /**
     * 计算某个词语的书写间隔（毫秒）。
     *
     * 公式：`clamp(字数 × 每字时长, 最小间隔, 最大间隔)`
     *
     * 按字数线性分配时间，是因为学生的书写耗时基本与字数成正比；
     * 同时用上下限兜住两个极端：单字词不至于来不及落笔，
     * 超长词也不至于让等待时间长得让人走神。
     *
     * @param word            词语
     * @param perCharSeconds  每个字的秒数
     * @param minSeconds      最小间隔秒数
     * @param maxSeconds      最大间隔秒数
     */
    fun intervalMillis(
        word: String,
        perCharSeconds: Float,
        minSeconds: Float,
        maxSeconds: Float,
    ): Long {
        // 按「可见字符数」算，忽略空白
        val charCount = word.count { !it.isWhitespace() }.coerceAtLeast(1)
        val raw = charCount * perCharSeconds
        val lo = minOf(minSeconds, maxSeconds)
        val hi = maxOf(minSeconds, maxSeconds)
        val seconds = raw.coerceIn(lo, hi)
        return (seconds * 1000f).toLong().coerceAtLeast(500L)
    }

    /**
     * 预估整套听写耗时（毫秒），用于在录入页给用户一个预期。
     * 只统计朗读间隔部分，不含实际发音时长（发音时长依赖引擎，无法精确预知）。
     */
    fun estimatedTotalMillis(
        words: List<String>,
        perCharSeconds: Float,
        minSeconds: Float,
        maxSeconds: Float,
    ): Long = words.sumOf { intervalMillis(it, perCharSeconds, minSeconds, maxSeconds) }

    /** 统计词语数（用于录入页实时计数） */
    fun countWords(raw: String): Int = parse(raw).size
}
