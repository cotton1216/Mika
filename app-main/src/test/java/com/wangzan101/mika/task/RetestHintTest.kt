package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.Provider
import org.junit.Assert.assertEquals
import org.junit.Test

class RetestHintTest {
    private val untested = Provider("p1", "Local", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:8000/v1", models = emptyList())
    private val hint = { name: String -> "retest $name" }

    /** 模型失败、主模型没测试通过:原因后面另起一行指向那家的「重新测试」。 */
    @Test fun modelFailureWithUntestedMainPointsToRetest() {
        val end = RetestHint.apply(TaskState.Failed("找不到该模型。", FailKind.Model), untested, hint)
        assertEquals(TaskState.Failed("找不到该模型。\nretest Local", FailKind.Model), end)
    }

    /** 主模型测过了、或不是模型的问题:原样返回。 */
    @Test fun otherEndsStayAsTheyAre() {
        val model = TaskState.Failed("找不到该模型。", FailKind.Model)
        assertEquals(model, RetestHint.apply(model, null, hint))
        val guard = TaskState.Failed("已安全停止。", FailKind.Guard)
        assertEquals(guard, RetestHint.apply(guard, untested, hint))
        assertEquals(TaskState.Cancelled, RetestHint.apply(TaskState.Cancelled, untested, hint))
    }
}
