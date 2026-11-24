package com.xxt.zl.ai.ruyiremote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 分组算法单测：分天边界、归属唯一、排序、空文件夹、时钟注入。 */
class SessionGroupsTest {

    private fun epochMs(date: LocalDate, hour: Int = 12): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, 0))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun session(id: String, updatedAt: Long) = SessionSummary(
        id = id,
        title = null,
        updatedAt = updatedAt,
        running = false,
        cwd = null,
        origin = null,
    )

    private fun sessionOf(
        id: String,
        updatedAt: Long,
        running: Boolean = false,
        cwd: String? = null,
    ) = SessionSummary(
        id = id,
        title = null,
        updatedAt = updatedAt,
        running = running,
        cwd = cwd,
        origin = null,
    )

    // 钉死一个周三作为 now：这样"今天/昨天/本周/更早"四桶都能构造出来，且不随真实日期漂移。
    // 选 2026-06-17（周三）；若系统时区导致跨日，用 hour=12 固定在中午避开边界。
    private val nowDate = LocalDate.of(2026, 6, 17)
    private val now = epochMs(nowDate)

    @Test
    fun `sessionDayOf splits today yesterday this-week and earlier by local calendar`() {
        assertEquals(SessionDay.TODAY, sessionDayOf(epochMs(nowDate, 0), now))
        assertEquals(SessionDay.TODAY, sessionDayOf(epochMs(nowDate, 23), now))
        assertEquals(SessionDay.YESTERDAY, sessionDayOf(epochMs(nowDate.minusDays(1)), now))
        // 本周一 = now - 2 天（周三），仍属本周。
        assertEquals(SessionDay.THIS_WEEK, sessionDayOf(epochMs(nowDate.minusDays(2)), now))
        // 上周日 = now - 3 天，落回更早。
        assertEquals(SessionDay.EARLIER, sessionDayOf(epochMs(nowDate.minusDays(3)), now))
        // 未来时间戳也归今天（对齐 dart 的 diff <= 0）。
        assertEquals(SessionDay.TODAY, sessionDayOf(epochMs(nowDate.plusDays(1)), now))
    }

    @Test
    fun `each session appears exactly once in folder or day block`() {
        val folder = RemoteFolder(id = "f1", name = "工作", sessionIds = listOf("s1"))
        val view = FolderView(folders = listOf(folder))
        val sessions = listOf(
            session("s1", now - 1_000),   // 归文件夹
            session("s2", now - 2_000),   // 归今天
            session("s3", now - 3_000),   // 归今天
        )
        val blocks = groupSessions(sessions, view, now)
        val ids = blocks.flatMap { it.items.map { s -> s.id } }

        assertEquals(3, ids.size)
        assertEquals(ids.toSet(), sessions.map { it.id }.toSet())
        assertEquals(listOf("s1"), (blocks[0] as FolderBlock).items.map { it.id })
    }

    @Test
    fun `blocks ordered folders then days with fixed day order`() {
        val folder = RemoteFolder(id = "f1", name = "工作", sessionIds = listOf("f-member"))
        val view = FolderView(folders = listOf(folder))
        val sessions = listOf(
            session("f-member", now),
            session("earlier", epochMs(nowDate.minusDays(10))),
            session("this-week", epochMs(nowDate.minusDays(2))),
            session("today", now - 500),
            session("yesterday", epochMs(nowDate.minusDays(1))),
        )
        val blocks = groupSessions(sessions, view, now)

        assertEquals(1, blocks.count { it is FolderBlock })
        assertEquals(
            listOf(SessionDay.TODAY, SessionDay.YESTERDAY, SessionDay.THIS_WEEK, SessionDay.EARLIER),
            blocks.filterIsInstance<DayBlock>().map { it.day },
        )
    }

    @Test
    fun `members sorted by updatedAt desc with id tie-break`() {
        val folder = RemoteFolder(id = "f1", name = "工作", sessionIds = listOf("a", "b", "c"))
        val view = FolderView(folders = listOf(folder))
        val sessions = listOf(
            session("a", 3_000), session("b", 3_000), session("c", 1_000),
        )
        val blocks = groupSessions(sessions, view, now)
        // a、b 同时间按 id 升序在前，c 最旧在后。
        assertEquals(listOf("a", "b", "c"), (blocks[0] as FolderBlock).items.map { it.id })
    }

    @Test
    fun `empty folder still emits a folder block`() {
        val view = FolderView(folders = listOf(RemoteFolder(id = "f1", name = "空文件夹")))
        val blocks = groupSessions(listOf(session("s1", now)), view, now)

        assertEquals(1, blocks.count { it is FolderBlock })
        assertTrue((blocks[0] as FolderBlock).items.isEmpty())
        assertEquals(1, blocks.count { it is DayBlock })
    }

    @Test
    fun `no folders yields pure day grouping`() {
        val sessions = listOf(session("s1", now), session("s2", epochMs(nowDate.minusDays(1))))
        val blocks = groupSessions(sessions, FolderView(), now)

        assertTrue(blocks.all { it is DayBlock })
        assertEquals(2, blocks.size)
    }

    // ---- T13：维度可切换 ----

    @Test
    fun `default dimension stays day for backward compatibility`() {
        // 不传 dimension 时应与 T12 完全一致：纯 DayBlock。
        val sessions = listOf(session("s1", now), session("s2", epochMs(nowDate.minusDays(1))))
        val blocks = groupSessions(sessions, FolderView(), now)

        assertTrue(blocks.all { it is DayBlock })
        assertEquals(2, blocks.size)
    }

    @Test
    fun `status dimension splits running and ended with running first`() {
        val sessions = listOf(
            sessionOf("ended-1", now - 1_000, running = false),
            sessionOf("running-1", now, running = true),
            sessionOf("running-2", now - 500, running = true),
        )
        val blocks = groupSessions(sessions, FolderView(), now, GroupDimension.STATUS)

        assertEquals(2, blocks.size)
        val first = blocks[0] as StatusBlock
        val second = blocks[1] as StatusBlock
        assertEquals(GroupStatus.RUNNING, first.status)
        assertEquals(GroupStatus.ENDED, second.status)
        assertEquals(listOf("running-1", "running-2"), first.items.map { it.id })
        assertEquals(listOf("ended-1"), second.items.map { it.id })
    }

    @Test
    fun `cwd dimension groups by last path segment and buckets uncwd as unspecified`() {
        val sessions = listOf(
            sessionOf("w1", now, cwd = "work/work"),
            sessionOf("w2", now - 1_000, cwd = "/home/me/work"),
            sessionOf("p1", now - 2_000, cwd = "/home/me/proj"),
            sessionOf("none", now - 3_000, cwd = null),
        )
        val blocks = groupSessions(sessions, FolderView(), now, GroupDimension.CWD)

        // 标签去重归并：两个 work 合成一块；按标签字典序 "proj" < "work" < "未指定目录"。
        val labels = blocks.map { (it as CwdBlock).label }
        assertEquals(listOf("proj", "work", "未指定目录"), labels)
        val work = blocks.first { (it as CwdBlock).label == "work" } as CwdBlock
        assertEquals(listOf("w1", "w2"), work.items.map { it.id })
        assertEquals(listOf("none"), (blocks.last() as CwdBlock).items.map { it.id })
    }

    @Test
    fun `folder stays on top and each session appears once under every dimension`() {
        val folder = RemoteFolder(id = "f1", name = "工作", sessionIds = listOf("f-member"))
        val view = FolderView(folders = listOf(folder))
        val sessions = listOf(
            sessionOf("f-member", now, cwd = "/x/folder"),
            sessionOf("a", now - 1_000, running = true, cwd = "/x/proj"),
            sessionOf("b", now - 2_000, running = false, cwd = "/x/proj"),
        )

        for (dimension in GroupDimension.values()) {
            val blocks = groupSessions(sessions, view, now, dimension)
            // 文件夹块恒为第一块，成员唯一归属，不随维度漂移。
            assertTrue(blocks.first() is FolderBlock)
            assertEquals(listOf("f-member"), (blocks.first() as FolderBlock).items.map { it.id })
            // 每条会话恰好出现一次。
            val ids = blocks.flatMap { it.items.map { s -> s.id } }
            assertEquals(3, ids.size)
            assertEquals(sessions.map { it.id }.toSet(), ids.toSet())
        }
    }

    @Test
    fun `dimension switch is stable for the same input`() {
        val sessions = listOf(
            sessionOf("a", now, running = true, cwd = "/p/one"),
            sessionOf("b", now - 1_000, running = false, cwd = "/p/two"),
        )
        // 同一输入、同一维度，两次调用产出结构相同（纯函数、无副作用、不依赖外部状态）。
        val first = groupSessions(sessions, FolderView(), now, GroupDimension.CWD)
        val second = groupSessions(sessions, FolderView(), now, GroupDimension.CWD)
        assertEquals(
            first.map { it to it.items.map { s -> s.id } },
            second.map { it to it.items.map { s -> s.id } },
        )
    }

    // ---- T14：按群维度 ----

    private fun titledSession(id: String, title: String?, updatedAt: Long = now) = SessionSummary(
        id = id,
        title = title,
        updatedAt = updatedAt,
        running = false,
        cwd = null,
        origin = null,
    )

    @Test
    fun `group dimension aggregates bot room titles by group name`() {
        // 真机实证：6 个成员会话标题均为「成员名 · 群名」。
        val sessions = listOf(
            titledSession("astro", "天文学代表 · 科学发展史五学科讨论", now),
            titledSession("bio", "生物代表 · 科学发展史五学科讨论", now - 1_000),
            titledSession("chem", "化学代表 · 科学发展史五学科讨论", now - 2_000),
        )
        val blocks = groupSessions(sessions, FolderView(), now, GroupDimension.GROUP)

        assertEquals(1, blocks.size)
        val group = blocks[0] as GroupBlock
        assertEquals("科学发展史五学科讨论", group.group)
        assertEquals(3, group.members.size)
        assertEquals(listOf("astro", "bio", "chem"), group.items.map { it.id })
    }

    @Test
    fun `group dimension rejects single-member group-like title and falls back to day`() {
        // 普通会话标题里偶然的「A · B」只出现一条 → 不造群，退回按天。
        val sessions = listOf(titledSession("lonely", "张三 · 项目复盘", now))
        val blocks = groupSessions(sessions, FolderView(), now, GroupDimension.GROUP)

        assertTrue(blocks.all { it is DayBlock })
        assertEquals(0, blocks.count { it is GroupBlock })
        assertEquals(1, blocks.count { it is DayBlock })
        assertEquals(listOf("lonely"), blocks.flatMap { it.items.map { s -> s.id } })
    }

    @Test
    fun `group dimension leaves plain sessions in day blocks and keeps each session once`() {
        val sessions = listOf(
            titledSession("g1", "小通 · 群A", now),
            titledSession("g2", "数学代表 · 群A", now - 1_000),
            titledSession("g3", "物理代表 · 群B", now - 2_000),
            titledSession("g4", "化学代表 · 群B", now - 3_000),
            titledSession("plain", "普通会话标题", now - 4_000),
            titledSession("no-title", null, now - 5_000),
        )
        val blocks = groupSessions(sessions, FolderView(), now, GroupDimension.GROUP)

        // 群 A、群 B 各成一块（群名按字典序），普通/无标题会话落回按天。
        assertEquals(
            listOf("群A", "群B"),
            blocks.filterIsInstance<GroupBlock>().map { it.group },
        )
        // 两条普通会话都在"今天"，合成 1 个 DayBlock。
        assertEquals(1, blocks.count { it is DayBlock })
        val ids = blocks.flatMap { it.items.map { s -> s.id } }
        assertEquals(sessions.size, ids.size)
        assertEquals(sessions.map { it.id }.toSet(), ids.toSet())
    }

    @Test
    fun `group dimension keeps folder on top and dedups member across sessions`() {
        val folder = RemoteFolder(id = "f1", name = "工作", sessionIds = listOf("f-member"))
        val view = FolderView(folders = listOf(folder))
        val sessions = listOf(
            // 文件夹成员即使标题像群成员也先进文件夹，不参与群聚合。
            titledSession("f-member", "天文学代表 · 群A", now),
            titledSession("m1", "小通 · 群A", now - 1_000),
            titledSession("m2", "小通 · 群A", now - 2_000),
            titledSession("m3", "数学代表 · 群A", now - 3_000),
        )
        val blocks = groupSessions(sessions, view, now, GroupDimension.GROUP)

        assertTrue(blocks.first() is FolderBlock)
        assertEquals(listOf("f-member"), (blocks.first() as FolderBlock).items.map { it.id })
        // 群 A 剩 3 条会话，但"小通"重复 → 2 个不同成员。
        val group = blocks.last() as GroupBlock
        assertEquals(2, group.members.size)
        assertEquals(setOf("小通", "数学代表"), group.members)
        val ids = blocks.flatMap { it.items.map { s -> s.id } }
        assertEquals(4, ids.size)
        assertEquals(sessions.map { it.id }.toSet(), ids.toSet())
    }
}
