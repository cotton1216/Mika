package com.xxt.zl.ai.ruyiremote.session

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 抽屉会话列表的一个分组块，对齐 dsh-Go `DrawerBlock`。
 *
 * 现有类型：
 * - [FolderBlock]：自定义文件夹及其成员；
 * - [DayBlock]：无文件夹的会话，按本地日历分天；
 * - [StatusBlock] / [CwdBlock]：T13 状态 / 目录维度；
 * - [GroupBlock]：T14 按群维度，从 title 的「成员名 · 群名」聚合。
 *
 * dsh-Go 另有 `PinnedBlock`（置顶）与 `FilteredBlock`（状态过滤），ruyuan 没有
 * workspace pins、也没有状态过滤入口，故本期不建模——不为不存在的 UI 预留空壳。
 */
sealed class SessionBlock {
    abstract val items: List<SessionSummary>
}

/** 一个文件夹 + 它的成员（成员顺序沿用排序后的会话顺序）。 */
data class FolderBlock(
    val folder: RemoteFolder,
    override val items: List<SessionSummary>,
) : SessionBlock()

/** 某一天的会话桶。块顺序固定为 TODAY → YESTERDAY → THIS_WEEK → EARLIER。 */
data class DayBlock(
    val day: SessionDay,
    override val items: List<SessionSummary>,
) : SessionBlock()

/** 运行状态桶：进行中 / 已结束。对齐 dsh-Go jobs 的 live/ended 二分。 */
data class StatusBlock(
    val status: GroupStatus,
    override val items: List<SessionSummary>,
) : SessionBlock()

/** 工作目录桶：按 cwd 末段目录名归并；无 cwd 的会话并入「未指定目录」。 */
data class CwdBlock(
    val label: String,
    override val items: List<SessionSummary>,
) : SessionBlock()

/**
 * 群聊桶（T14）：小通 Bot 群成员会话的 title 被钉成「成员名 · 群名」，
 * 按群名归并后成块。[group] 为群名，[members] 为成员名去重集合（用于块头展示人数）。
 */
data class GroupBlock(
    val group: String,
    val members: Set<String>,
    override val items: List<SessionSummary>,
) : SessionBlock()

/** 本地日历分天桶，对齐 dsh-Go `SessionDay`（L457）。 */
enum class SessionDay { TODAY, YESTERDAY, THIS_WEEK, EARLIER }

/** 可切换的分组维度。DAY 为 T12 既有形态，作为默认值兼容旧调用；GROUP 为 T14 新增。 */
enum class GroupDimension { DAY, STATUS, CWD, GROUP }

/** 运行状态桶，取 `SessionSummary.running`。 */
enum class GroupStatus { RUNNING, ENDED }

/**
 * 从 cwd 取末段目录名作为分组标签，口径与 `SessionSummary.displayTitle` 一致
 * （同时切 `/` 与 `\`，兼容 Windows 路径）；空 cwd 归「未指定目录」。
 */
fun cwdLabel(cwd: String?): String =
    cwd?.trimEnd('/', '\\')
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
        ?: "未指定目录"

/**
 * 从小通 Bot 群成员会话的 title 解析（成员名, 群名），格式「成员名 · 群名」。
 * 依据是 `xiaotong-bot/team.ts` 的 `bindMemberConversation(..., "${bot.name} · ${group.name}", ...)`，
 * 已在真机 session/list 抓到实证（天文学代表 · 科学发展史五学科讨论 等 6 条）。
 *
 * 严格口径防误伤普通会话：
 * - 只认空格包裹的 ` · ` 分隔符（普通标题里的 `·` 多半无空格）；
 * - 只切一次，成员名/群名都须非空；
 * - 群名里若还含 ` · `，归到群名一侧（取最后一次出现，让「成员 · 群」稳定）。
 * 返回 null 表示不是群成员标题，按群维度下走普通按天分桶。
 */
private fun parseGroupTitle(title: String?): Pair<String, String>? {
    if (title.isNullOrBlank()) return null
    val sep = " · "
    val at = title.indexOf(sep)
    if (at <= 0) return null
    val member = title.substring(0, at).trim()
    val group = title.substring(at + sep.length).trim()
    if (member.isEmpty() || group.isEmpty()) return null
    return member to group
}

/**
 * 按本地日历把时间戳归入某一天，周一为一周起点；对齐 dsh-Go `sessionDayOf`（L460–470）。
 *
 * - 今天及未来（在当天 0 点之后）都归 TODAY——与 dart 侧 `diff <= 0` 一致；
 * - 昨天独立成桶；
 * - 本周（含周一、不含上周）归 THIS_WEEK；
 * - 更早归 EARLIER。
 *
 * [now] 与 [epochMs] 都是 epoch 毫秒，时区取系统默认（与 `formatWhen` 的 Calendar 口径一致）。
 */
fun sessionDayOf(epochMs: Long, now: Long): SessionDay {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val diff = ChronoUnit.DAYS.between(day, today) // today - day
    if (diff <= 0L) return SessionDay.TODAY
    if (diff == 1L) return SessionDay.YESTERDAY
    val monday = today.minusDays(today.dayOfWeek.value - 1L)
    if (!day.isBefore(monday)) return SessionDay.THIS_WEEK
    return SessionDay.EARLIER
}

/**
 * 把会话切成有序分组块，对齐 dsh-Go `drawerBlocks`（L656–723）。纯函数，不读全局时钟以外的状态。
 *
 * 契约：**每条会话恰好出现一次**——在文件夹里就不进按天块，反之亦然。文件夹归属由
 * [view] 的 `folderOf` 决定（一个会话至多一个文件夹，T11 写操作已保证）。
 *
 * 排序：`updatedAt` 倒序，`id` 作 tie-break（对齐 dart 侧的二级比较）。dsh-Go 在
 * `DrawerSort.created` 下用 `createdAt[s.sessionId] ?? s.updatedAt`；ruyuan 的
 * [SessionSummary] 没有 createdAt 字段，两种排序都退化为 updatedAt，因此 [view].sort
 * 本期不影响结果——保留参数是为了让 UI 接线与数据结构一步到位，不制造假能力。
 *
 * [dimension] 只影响「未归文件夹的会话」如何分桶：文件夹块在所有维度下常驻顶层，
 * 保证分类归属始终可见、不随维度切换而丢失。DAY 维持 T12 四桶；STATUS 二分；
 * CWD 按目录名归并。
 *
 * @param now 当前时刻（epoch 毫秒）注入，便于单测钉死时钟；UI 侧取 `System.currentTimeMillis()`。
 * @param dimension 分组维度，默认 [GroupDimension.DAY] 兼容 T12 旧调用。
 */
fun groupSessions(
    sessions: List<SessionSummary>,
    view: FolderView,
    now: Long,
    dimension: GroupDimension = GroupDimension.DAY,
): List<SessionBlock> {
    val sorted = sessions.sortedWith(
        compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.id },
    )
    val folderOf = view.folderOf

    val blocks = mutableListOf<SessionBlock>()
    // 文件夹块沿用 view.folders 的顺序；空文件夹也出块（dsh-Go 非 scoped 分支如此），
    // 让后续管理入口有落点，也让用户看到"建了但还没放会话"的文件夹。
    // T13：文件夹块在所有维度下都先输出，常驻顶层。
    for (folder in view.folders) {
        val members = sorted.filter { folderOf[it.id] == folder.id }
        blocks += FolderBlock(folder, members)
    }

    // 剩余会话按所选维度分桶。每条会话恰好落一个桶（文件夹成员已在上一步排除）。
    val rest = sorted.filterNot { folderOf.containsKey(it.id) }
    when (dimension) {
        GroupDimension.DAY -> {
            val days = mutableMapOf<SessionDay, MutableList<SessionSummary>>()
            for (s in rest) {
                days.getOrPut(sessionDayOf(s.updatedAt, now)) { mutableListOf() }.add(s)
            }
            // 块顺序固定：TODAY → YESTERDAY → THIS_WEEK → EARLIER。
            for (day in SessionDay.values()) {
                val items = days[day]
                if (!items.isNullOrEmpty()) blocks += DayBlock(day, items)
            }
        }

        GroupDimension.STATUS -> {
            // 进行中在前、已结束在后；对齐 dsh-Go jobs 的 live/ended 顺序。
            val running = rest.filter { it.running }
            val ended = rest.filterNot { it.running }
            if (running.isNotEmpty()) blocks += StatusBlock(GroupStatus.RUNNING, running)
            if (ended.isNotEmpty()) blocks += StatusBlock(GroupStatus.ENDED, ended)
        }

        GroupDimension.CWD -> {
            // 按目录名归并，桶顺序按标签字典序——保证稳定，不随列表到达顺序漂移。
            val byLabel = mutableMapOf<String, MutableList<SessionSummary>>()
            for (s in rest) {
                byLabel.getOrPut(cwdLabel(s.cwd)) { mutableListOf() }.add(s)
            }
            for (label in byLabel.keys.sorted()) {
                blocks += CwdBlock(label, byLabel.getValue(label))
            }
        }

        GroupDimension.GROUP -> {
            // 先把 title 符合「成员名 · 群名」的会话按群名归堆，同时记下各自成员名。
            // 用两个独立 map，避免局部数据类成员与外层参数 sessions 同名被 += 误解析。
            val membersByGroup = mutableMapOf<String, MutableSet<String>>()
            val itemsByGroup = mutableMapOf<String, MutableList<SessionSummary>>()
            val loose = mutableListOf<SessionSummary>()
            for (s in rest) {
                val parsed = parseGroupTitle(s.title)
                if (parsed == null) {
                    loose.add(s)
                } else {
                    val (member, group) = parsed
                    membersByGroup.getOrPut(group) { mutableSetOf() }.add(member)
                    itemsByGroup.getOrPut(group) { mutableListOf() }.add(s)
                }
            }
            // 防误判：只有 ≥2 个不同成员名的群才成块；单个成员（可能是普通会话标题里
            // 偶然出现的「A · B」）退回按天块，避免凭一条会话造出一个"群"。
            val realGroups = membersByGroup.filterValues { it.size >= 2 }.keys
            val leftover = loose + itemsByGroup.filterKeys { it !in realGroups }.values.flatten()
            for (group in realGroups.sorted()) {
                blocks += GroupBlock(group, membersByGroup.getValue(group).toSet(), itemsByGroup.getValue(group))
            }
            // 非群会话仍按天分桶，保证每条会话恰好出现一次，且不丢普通会话。
            val days = mutableMapOf<SessionDay, MutableList<SessionSummary>>()
            for (s in leftover) {
                days.getOrPut(sessionDayOf(s.updatedAt, now)) { mutableListOf() }.add(s)
            }
            for (day in SessionDay.values()) {
                val items = days[day]
                if (!items.isNullOrEmpty()) blocks += DayBlock(day, items)
            }
        }
    }
    return blocks
}
