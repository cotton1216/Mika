package com.wangzan101.mika.browser

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.task.TaskService.Companion.isActive

/** 浏览器页底部此刻是什么:平时的输入条、半屏对话面板、收起来的小卡片。 */
enum class PageAskMode { Bar, Panel, Card }

/**
 * 在浏览器页上问如意 AI 那一块怎么显示(纯逻辑)。[state] 是这一页的对话里此刻那件事的状态(没有为 null),
 * [open] 是面板开着(用户问了、点开了小卡片、如意 AI 要你回话时打开;用户收起、你在页面上动手时收起)。
 * - 面板开着就是面板;
 * - 收着、那件事还没结束:底部一张小卡片(说它此刻在做什么);你在页面上动手而暂停时卡片写「已暂停」,顶上一条「你在操作，如意 AI 已暂停 · 继续」;
 * - 其余(没有任务、已经结束又收起来了):平时的输入条。
 */
object PageAskModel {
    fun mode(state: TaskState?, open: Boolean): PageAskMode = when {
        open -> PageAskMode.Panel
        state != null && state.isActive() -> PageAskMode.Card
        else -> PageAskMode.Bar
    }

    /** 你在页面上亲手操作,如意 AI 停着等你点「继续」。 */
    fun pausedForUser(state: TaskState?): Boolean =
        state is TaskState.Held && state.reason.code == "paused" && state.reason.why == TaskGate.USER_ON_PAGE

    /** 页面四周亮一圈光边:如意 AI 正在干活(不是在等你、不是暂停)。 */
    fun glow(state: TaskState?): Boolean = state is TaskState.Running

    /** 如意 AI 要你回话(回答、同意、接手、动一下手):从别的状态进到这里时面板自己打开。 */
    fun needsYou(state: TaskState?): Boolean = when (state) {
        is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> true
        is TaskState.Held -> state.reason.needsYou
        else -> false
    }

    /**
     * 状态从 [before] 变成 [after] 时面板该怎么变:true 打开、false 收起、null 不动。
     * 你在页面上动手而暂停时收起成小卡片(让开页面);如意 AI 新要你回话时打开。
     */
    fun follow(before: TaskState?, after: TaskState?): Boolean? = when {
        pausedForUser(after) && !pausedForUser(before) -> false
        needsYou(after) && !needsYou(before) -> true
        else -> null
    }
}
