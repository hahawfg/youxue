package cn.com.toobug.youxue.task

import cn.com.toobug.youxue.data.TaskItem

/**
 * 任务的手动排序。
 *
 * 排序权重存在 [TaskItem.order] 上，**越大越靠上**。这里只做三件事：
 * 定下「怎么比大小」、决定新任务的权重、以及把一次拖动换算成一组新的权重。
 *
 * 全部是不依赖 Android 的纯函数，拖动这种「错了就很难察觉」的逻辑必须能被单测钉住——
 * 排序一乱，用户看到的是任务位置莫名其妙地跳，还找不回原来的顺序。
 */
object TaskOrder {

    /** 重排后的编号步长。留出空隙纯粹是为了让数值好读，不代表要往里插新值 */
    private const val STEP = 1000L

    /**
     * 显示顺序：权重大的在前。
     *
     * 后两级只是为了**确定性**——两条任务权重相同时（老数据都回落到创建时刻，
     * 同一毫秒创建的两条就会撞），没有兜底比较键的话顺序会随排序算法内部实现漂移，
     * 表现为「每次进页面顺序都不一样」。
     */
    fun sorted(tasks: List<TaskItem>): List<TaskItem> = tasks.sortedWith(
        compareByDescending<TaskItem> { it.order }
            .thenByDescending { it.createdAt }
            .thenBy { it.id }
    )

    /**
     * 新任务的权重。
     *
     * 取「当前时刻」而不是「最大值 + 1」：重排会把权重压成几千这种小数字，
     * 而时刻是 1.7e12 量级，天然比它们都大——于是「新建的排最上面」这条不依赖
     * 任何额外状态就成立了，也不用为它单开一个字段。
     * 与最大值兜底，是为了防住「某条数据被写进了离谱的大数」这种脏数据。
     */
    fun newOrder(tasks: List<TaskItem>, now: Long): Long =
        maxOf(now, (tasks.maxOfOrNull { it.order } ?: 0L) + 1L)

    /**
     * 把 [movedId] 插到同组第 [insertAt] 位，返回整份新列表。
     *
     * [groupIds] 是**拖动发生时这一组在屏幕上的顺序**（含被拖的那条），
     * [insertAt] 是「去掉被拖那条之后」的插入位——界面按手指落在哪两张卡片之间
     * 算出来的就是这个口径，中间不再换算一次，少一处能算错的地方。
     *
     * 重新编号是**整份列表**一起做，而不是只给这一组：一个任务属于哪一组会随日期变，
     * 只给组内编号的话，两组各自从 1000 数起，任务换组时权重就会和组里另一条撞上，
     * 位置于是变成「看 createdAt 的运气」。整份编号保证权重全局唯一且相对顺序不变。
     */
    fun move(
        all: List<TaskItem>,
        groupIds: List<String>,
        movedId: String,
        insertAt: Int,
    ): List<TaskItem> {
        val byId = all.associateBy { it.id }
        val group = groupIds.mapNotNull { byId[it] }.toMutableList()
        val from = group.indexOfFirst { it.id == movedId }
        if (from < 0) return all

        val before = group.map { it.id }
        val moved = group.removeAt(from)
        group.add(insertAt.coerceIn(0, group.size), moved)

        // 拖回原位：不重排，也不写盘。拖动最后落回原处是最常见的结果之一
        if (group.map { it.id } == before) return all

        // 按全局顺序铺开，把这一组成员占的那几个位置依次换成新顺序。
        // 成员在全局序列里是散开的，但**相对顺序**一致，换进去之后组内顺序就对了，
        // 其余任务彼此之间的顺序一点没动
        val movedIds = group.map { it.id }.toSet()
        var cursor = 0
        val merged = sorted(all).map { t ->
            if (t.id in movedIds) group[cursor++] else t
        }
        return renumber(merged)
    }

    /** 整体重编号：第一条最大，最后一条最小，步长 [STEP] */
    private fun renumber(ordered: List<TaskItem>): List<TaskItem> {
        val n = ordered.size
        return ordered.mapIndexed { i, t -> t.copy(order = (n - i) * STEP) }
    }
}
