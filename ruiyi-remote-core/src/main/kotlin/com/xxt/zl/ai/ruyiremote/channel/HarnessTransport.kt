package com.xxt.zl.ai.ruyiremote.channel

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 会话层只需要这两件事；生产实现是 [HarnessTunnel]，测试可以替换 Host 边界。 */
interface HarnessTransport {
    suspend fun call(endpoint: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long? = null): JsonElement?
    fun stream(endpoint: String, args: JsonObject): Flow<JsonElement>
}
