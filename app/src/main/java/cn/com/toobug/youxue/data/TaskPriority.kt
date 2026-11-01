package cn.com.toobug.youxue.data

/**
 * 任务的紧急程度。
 *
 * **只影响显示颜色，不参与排序**——列表顺序完全由用户手动拖出来。
 * 一旦让紧急度也参与排序，用户辛苦拖好的位置会被「改一下紧急度」当场冲掉，
 * 而改紧急度本来只是想在卡片上做个记号。
 *
 * 四档而不是三档：三档缺一个「不急但也不能忘」的落点，
 * 用户要么把闲事标成「普通」污染中段，要么干脆不标。
 */
enum class TaskPriority(
    /** 卡片的颜色条与编辑弹层里的档位名 */
    val label: String,
) {
    URGENT("紧急"),
    HIGH("较高"),
    NORMAL("普通"),
    LOW("不急"),
    ;

    companion object {
        /** 默认档。绝大多数任务是普通任务，让「不选」这件事有个确定的结果 */
        val DEFAULT = NORMAL

        /** 未知字符串落回默认档，而不是抛异常——坏数据不该让整份列表打不开 */
        fun fromName(raw: String): TaskPriority =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
