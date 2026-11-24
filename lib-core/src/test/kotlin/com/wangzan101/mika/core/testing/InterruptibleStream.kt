package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/**
 * 给 [inner] 套一层流式输出,用来测插话打断:第一次请求依次送出 [chunks](每段是到此为止的可见全文,
 * 空串表示重来、清掉),然后停住,直到被撤掉;这次请求不记进 [inner]。之后的请求照 [inner] 的剧本回复,整段文字一次送出。
 */
class InterruptibleStream(private val inner: FakeChatModel, private val chunks: List<String>) : StreamingChatModel {
    override val name: String get() = inner.name
    override val capabilities: Capabilities get() = inner.capabilities
    /** 第一次请求已经送完 [chunks]、停住了。 */
    val entered = CompletableDeferred<Unit>()
    /** 第一次请求被撤掉了。 */
    var cancelled = false
        private set
    private var first = true

    /** 不流式问(界面不显示实时文字)时第一次请求同样停住,只是什么字也没送出。 */
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        if (first) {
            first = false
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        return inner.complete(system, messages, tools)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        if (first) {
            first = false
            chunks.forEach(onText)
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        return inner.complete(system, messages, tools).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
    }
}
