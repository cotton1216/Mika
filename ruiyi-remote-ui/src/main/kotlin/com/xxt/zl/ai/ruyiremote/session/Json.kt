package com.xxt.zl.ai.ruyiremote.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 协议里的字段都是「可能缺席、类型不一定对」的弱结构。这些扩展把取值收成一处，
 * 让调用点只关心业务判断，不用每次写一串 `as? JsonObject`。
 */

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): List<kotlinx.serialization.json.JsonElement> =
    (this[key] as? JsonArray) ?: emptyList()

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

/**
 * 数字字段。协议里整数值偶尔会以 `12.0` 这种浮点写法出现（JS 侧没有 int/float 之分），
 * 所以两种都要认；字符串形式的数字**不认**，避免把 id 之类的文本当成计数。
 */
internal fun JsonObject.num(key: String): Long? {
    val raw = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content ?: return null
    return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
}

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

internal fun JsonPrimitive.contentOrNullSafe(): String = if (this is JsonNull) "" else content
