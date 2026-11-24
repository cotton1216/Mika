package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.Provider

/**
 * 任务因模型失败,而主模型在它那家还没测试通过时([untested],见 [com.wangzan101.mika.settings.ProviderStore.untestedMainProvider]):
 * 在原因后面另起一行,指向那家的「重新测试」([hint] 拿服务商名字给出这一句)。别的结局原样返回。
 */
internal object RetestHint {
    fun apply(end: TaskState, untested: Provider?, hint: (String) -> String): TaskState =
        if (end is TaskState.Failed && end.kind == FailKind.Model && untested != null) {
            end.copy(reason = end.reason + "\n" + hint(untested.name))
        } else end
}
