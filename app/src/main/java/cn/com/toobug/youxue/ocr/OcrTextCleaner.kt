package cn.com.toobug.youxue.ocr

import cn.com.toobug.youxue.dictation.WordParser

/**
 * 一行识别结果在填进文本框之前的样子。
 *
 * [suspect] 不是 null 表示「这行看着不像要听写的词语」。
 * 这类行**仍然展示**，只是默认不勾选——丢弃和保留的决定权在用户，
 * 程序只负责把可疑的地方指出来。OCR 的输出没有绝对的可信度，
 * 任何一刀切的过滤都会在某些场景下吃掉用户真正想要的内容。
 */
data class OcrCandidate(
    val text: String,
    val suspect: SuspectReason? = null,
) {
    val selectedByDefault: Boolean get() = suspect == null
}

/** 可疑原因。文案由 UI 层决定，这里只给语义 */
enum class SuspectReason {
    /** 全是数字，多半是页码或题号 */
    NUMERIC_ONLY,

    /** 全是符号，没有可听写的内容 */
    PUNCTUATION_ONLY,

    /** 只剩一个字符，很可能是被切碎的残字 */
    TOO_SHORT,

    /** 太长，多半是句子而不是词语。按「词元」算，不按字符 */
    TOO_LONG,
}

/**
 * OCR 结果清洗。
 *
 * 刻意做成纯函数（不碰 Android 类）——**OCR 本身没法单测，但清洗能**，
 * 而清洗恰恰是最容易出错、也最需要回归保护的一段：真实拍照的输入
 * 千奇百怪（字间空格、全角字符、行首题号、页眉页码），
 * 每加一条规则都可能碰坏另一条。
 */
object OcrTextCleaner {

    /**
     * 超过这么多「词元」就当成「多半是句子」。小学词语以 2～4 字为主。
     *
     * 单位是**词元**不是字符：一个汉字算一个词元、一个西文单词也算一个词元。
     * 直接数字符是错的——`good morning` 去掉空格有 11 个字符，会被误判成句子，
     * 而它在英文里只是两个词。
     */
    const val SUSPECT_LONG_TOKENS = 10

    /** 首尾会剥掉的装饰性符号——OCR 经常把分隔线、项目符号识别成行首字符 */
    private const val EDGE_NOISE = "·•—–＿_|｜~＊*"

    /**
     * 一次剥掉首尾的「噪音符号 + 空白」连续段。
     *
     * 用正则而不是 `trim(chars)` 两遍：`"· · ·"` 这种「符号和空白交替」
     * 的行，`trim` 剥掉开头的 `·` 就停在空格上，剩下的还是噪声；
     * 一行全是噪声却剥不干净，就会在预览页里多出一个空勾选框。
     */
    private val EDGE_NOISE_RE = Regex("^[\\s$EDGE_NOISE]+|[\\s$EDGE_NOISE]+$")

    /**
     * 确定性分隔符：顿号、逗号（全角/半角）。
     *
     * 出现在这里就是明确的词语边界——中文词语内部不会出现顿号逗号，
     * 所以这种分隔不需要任何猜测，见到就断。
     */
    private const val HARD_SEPARATORS = "、，,"

    private val HARD_SPLIT = Regex("[$HARD_SEPARATORS]")

    /** 疑似分隔符：空格。是不是真的边界还得看上下文，判定见 [splitSegment] */
    private val SOFT_SPLIT = Regex("[ \\t]+")

    /** 连续的空白压成一个，方便后面的切分 */
    private val RUNS_OF_SPACE = Regex("[ \\t]{2,}")

    /** 含任意西文字母。用来把「英文词组」和「中文词表」两类行分开处理 */
    private val LATIN = Regex("[A-Za-z]")

    /**
     * 把引擎吐出的原始行，整理成可以给用户勾选的候选列表。
     *
     * 空行会被丢掉——它没有信息量，不值得占一个勾选框。
     *
     * 一行**可能变成多个候选**：横排词表 `苹果 香蕉 橘子` 在识别结果里是一行，
     * 但它其实是三个词。拆分的判定见 [splitWords]。
     */
    fun toCandidates(rawLines: List<String>): List<OcrCandidate> =
        rawLines.asSequence()
            .map { cleanLine(it) }
            .filter { it.isNotEmpty() }
            .flatMap { splitWords(it) }
            .map { OcrCandidate(it, judge(it)) }
            .toList()

    /**
     * 清洗单行，但**不管词怎么分**（那是 [splitWords] 的事）。
     *
     * 这里曾经有一条「汉字之间的空格一律删掉」的规则，用来修 OCR 常见的
     * 逐字识别噪声（`小 朋 友` → `小朋友`）。但那条规则**只看两侧是不是汉字**，
     * 区分不了两种形态完全一样的输入：
     * - `小 朋 友`：一个字一个字断开，是噪声，要**合并**
     * - `苹果 香蕉`：两个完整的词，是词表横排，要**拆开**
     *
     * 一律删掉就把后者也粘成了 `苹果香蕉`，用户永远看不出这里其实有两个词。
     * 现在这件事整体交给 [splitWords] 判断，它手里有「片段长度」这个额外信息。
     */
    fun cleanLine(raw: String): String {
        var s = raw.replace('\u3000', ' ').replace('\u00A0', ' ')
        s = toHalfWidthAlnum(s)
        s = RUNS_OF_SPACE.replace(s, " ")
        s = s.trim()
        // 先剥首尾噪声再剥序号：`· 1. 苹果` 得先去掉 `· ` 才认得出 `1. ` 是序号，
        // 顺序反过来就只剥掉一半，预览页里会留下一个带序号的丑行
        s = EDGE_NOISE_RE.replace(s, "").trim()
        s = WordParser.stripIndexPrefix(s)
        return s.trim()
    }

    /**
     * 把清洗后的一行切成若干词语。
     *
     * 分隔点分两档：
     * - **顿号逗号**是确定的边界，见到就断；
     * - **空格**要判断，因为它在两种场景里含义相反（`小 朋 友` 是噪声、
     *   `苹果 香蕉` 是边界），判定规则见 [splitSegment]。
     *
     * 返回空列表表示这行没内容（例如整行只有一个顿号）。
     */
    fun splitWords(line: String): List<String> {
        if (line.isEmpty()) return emptyList()
        return HARD_SPLIT.split(line)
            .flatMap { splitSegment(it) }
            .filter { it.isNotEmpty() }
    }

    /**
     * 处理一个「段」（已被顿号逗号切开的片段）内部的空格。
     *
     * 三种情况：
     *
     * 1. **含西文字母 → 不拆词**，整段当一个听写单位。英文的词组本来就是一整条
     *    （`good morning`、`happy new year`），按空格拆开就毁了；中英对照的条目
     *    （`苹果 apple`）同理。这条也跟 [judge] 按「词元」计长度的口径一致——
     *    那边本来就假定英文词组占一行。
     *    不过**汉字之间被切碎的空格仍然顺手修掉**（`苹 果 apple` → `苹果 apple`）：
     *    不修的话，混排行里的逐字噪声就永远没人管了。
     *
     * 2. **片段全是单字符 → 合并回一个词**。这是 OCR 的逐字识别噪声
     *    （`小 朋 友` → `小朋友`），也是这套逻辑要守住的原行为。
     *
     * 3. **其余 → 按空格断词**，但把**连续的单字片段**先并起来：
     *    `苹果 香 蕉` 里「香」「蕉」显然是被空格切碎的同一个词，
     *    而「苹果」是完整的。并完得 `[苹果, 香蕉]`，比拆成三个词正确得多。
     *
     * 已知的取舍：`小 朋友`（逐字噪声里夹着一个完整的词）会被判成
     * `[小, 朋友]`。这种形态从字符层面无法与真正的两个词区分，
     * 而拆错的代价是可控的——`小` 会被 [judge] 标成「只有一个字」、
     * 默认不勾选，用户在预览页一眼就能看见。反过来「合错了」是**没有痕迹**的：
     * `苹果香蕉` 看着就是个正常词，用户不会察觉。
     * 所以这里的倾向是：宁可拆，拆错要留提示。
     */
    private fun splitSegment(segment: String): List<String> {
        val s = segment.trim()
        if (s.isEmpty()) return emptyList()

        val parts = SOFT_SPLIT.split(s).filter { it.isNotEmpty() }
        if (parts.size <= 1) return listOf(s)

        if (parts.any { LATIN.containsMatchIn(it) }) {
            return listOf(mergeLoneCjkRuns(parts).joinToString(" "))
        }

        if (parts.all { it.length == 1 }) return listOf(parts.joinToString(""))

        return mergeLoneCjkRuns(parts)
    }

    /**
     * 把连续的单字汉字片段并成一个词，其余片段各自成词。
     *
     * `[苹果, 香, 蕉]` → `[苹果, 香蕉]`
     * `[苹果, 香, 蕉, 橘子]` → `[苹果, 香蕉, 橘子]`
     * `[小, 朋友]` → `[小, 朋友]`（单个单字片段没有同伴，只能自己站着）
     * `[苹, 果, apple, 香, 蕉]` → `[苹果, apple, 香蕉]`
     *
     * 只并**单字汉字**：单个西文字母（`a`）或单个数字（`1`）都不并，
     * 它们通常是独立条目，不是被切碎的汉字。
     */
    private fun mergeLoneCjkRuns(parts: List<String>): List<String> {
        val out = ArrayList<String>(parts.size)
        val run = StringBuilder()
        fun flushRun() {
            if (run.isNotEmpty()) {
                out += run.toString()
                run.setLength(0)
            }
        }
        for (p in parts) {
            if (p.length == 1 && isCjk(p[0])) {
                run.append(p)
            } else {
                flushRun()
                out += p
            }
        }
        flushRun()
        return out
    }

    /** 把候选行拼成文本框内容。分隔符只在这里定义一次 */
    fun joinLines(lines: List<String>): String =
        lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /**
     * 全角字母/数字转半角。
     *
     * 只动字母和数字，**不碰标点**：中文标点（，。！？）被转成半角会显得很怪，
     * 而标点本来也不是听写的内容，没有规范化的必要。
     * OCR 输出全角 `ＡＢＣ`、`１２３` 是常见现象。
     */
    private fun toHalfWidthAlnum(s: String): String {
        if (s.none { isFullWidthAlnum(it) }) return s
        return buildString(s.length) {
            for (c in s) {
                append(if (isFullWidthAlnum(c)) (c.code - 0xFEE0).toChar() else c)
            }
        }
    }

    private fun isFullWidthAlnum(c: Char): Boolean =
        c in '\uFF10'..'\uFF19' || // ０-９
            c in '\uFF21'..'\uFF3A' || // Ａ-Ｚ
            c in '\uFF41'..'\uFF5A' // ａ-ｚ

    private fun judge(text: String): SuspectReason? {
        val visible = text.filterNot { it.isWhitespace() }
        if (visible.isEmpty()) return SuspectReason.PUNCTUATION_ONLY
        // isLetterOrDigit 对汉字返回 true，所以这里能正确区分「真的没内容」和「有字」
        if (visible.none { it.isLetterOrDigit() }) return SuspectReason.PUNCTUATION_ONLY
        if (visible.all { it.isDigit() }) return SuspectReason.NUMERIC_ONLY
        if (visible.length == 1) return SuspectReason.TOO_SHORT
        if (tokenCount(text) > SUSPECT_LONG_TOKENS) return SuspectReason.TOO_LONG
        return null
    }

    /**
     * 数「词元」：一个汉字一个、一个西文单词一个。
     *
     * 这是唯一一个对中英都成立的「长度」口径。按字符数算的话，
     * `中华人民共和国`（7 字）和 `happy new year`（14 字符）会被放在
     * 完全不同的尺度上比较，而它们其实是同一个量级的词组。
     *
     * 数字和标点一律断开西文单词——`good-morning` 算一个词，
     * `good morning` 算两个，都符合直觉。
     */
    private fun tokenCount(text: String): Int {
        var cjk = 0
        var latinWords = 0
        var inLatinWord = false
        for (c in text) {
            when {
                isCjk(c) -> {
                    cjk++
                    inLatinWord = false
                }

                c.isLetterOrDigit() -> {
                    if (!inLatinWord) {
                        latinWords++
                        inLatinWord = true
                    }
                }

                else -> inLatinWord = false
            }
        }
        return cjk + latinWords
    }

    private fun isCjk(c: Char): Boolean =
        c in '\u3400'..'\u4DBF' || // 扩展 A 区
            c in '\u4E00'..'\u9FFF' || // 基本区
            c in '\uF900'..'\uFAFF' || // 兼容区
            c in '\u3040'..'\u30FF' // 平假名 / 片假名
}
