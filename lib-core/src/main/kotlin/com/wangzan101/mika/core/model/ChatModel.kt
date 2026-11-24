package com.wangzan101.mika.core.model

interface ChatModel {
    val capabilities: Capabilities

    /** 模型名,只用于记录。 */
    val name: String get() = ""

    /** 失败时抛 [ModelError]。 */
    suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply
}

/** 支持逐段文字的模型；不支持的实现继续走 [ChatModel.complete]。 */
interface StreamingChatModel : ChatModel {
    /**
     * [onText] 收到的是本次请求已生成的可见文字全文；不包含模型私有思考或工具参数。
     * [onThinking] 收的是已生成的思考全文(模型不返回思考或这家实现不支持时永远不调用)。
     */
    suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
        onThinking: (String) -> Unit = {},
    ): ModelReply
}
