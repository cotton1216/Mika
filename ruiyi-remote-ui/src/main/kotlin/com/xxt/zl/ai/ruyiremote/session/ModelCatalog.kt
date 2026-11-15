package com.xxt.zl.ai.ruyiremote.session

import kotlinx.serialization.json.JsonObject

/*
 * 模型目录：Host 通过 `session/modelCatalog` 给的一份「这台电脑现在能用什么模型」的
 * 快照，以及会话级的当前选择。
 *
 * 之前在手机上完全不显示模型，用户看到的永远是桌面端恰好设过的那个默认值，还会
 * 出现「我在桌面换了模型，手机还在按老模型说话」的错位。这个文件把协议里本来就
 * 有的三样东西取全：
 *   - `session/modelCatalog` 的分组、模型、描述、徽标、推理强度；
 *   - `session/selectModel` 的切换；
 *   - `session/control` / follow 快照里的 `modelSelection` 投影（Host 是唯一真相）。
 */

/** 一次完整的模型选择，与 Host 的 `ModelSelection` 同构。 */
data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
) {
    /** 判断「是不是同一个模型」时忽略推理强度：换强度不该被当成换模型。 */
    fun sameRoute(other: ModelSelection): Boolean =
        provider == other.provider && model == other.model
}

/** 一个可选的推理强度（`low` / `high` 之类，由适配器定义）。 */
data class ModelEffort(val id: String, val name: String, val description: String? = null)

/**
 * Host 对部分适配器只给 id、`name` 与 id 相同。直接显示 `gpt-6.1-sol` 在手机上像
 * 一串内部编码，所以按 id 排版成人读的样子：连字符分段、纯数字段保留小数点、其余
 * 首字母大写。这只是排版，不改变任何字面内容。
 */
internal fun readableModelId(id: String): String {
    if (id.isBlank()) return id
    return id.split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            if (token.all { it.isDigit() || it == '.' }) token
            else token.replaceFirstChar { it.uppercaseChar() }
        }
}

/** 目录里的一个模型。 */
data class CatalogModel(
    val id: String,
    val name: String,
    val description: String?,
    /** Provider 自带的角标文案，纯展示用。 */
    val badge: String?,
    val efforts: List<ModelEffort>,
    val defaultEffort: String?,
    val badgeTone: String? = null,
) {
    /** 列表里显示的标题：Host 的名字与 id 相同时排版成可读形式。 */
    val label: String
        get() = name.takeIf { it.isNotBlank() && it != id } ?: readableModelId(id)
}

/** 一个 provider 及其下可用的模型。 */
data class ModelGroup(
    val id: String,
    val name: String,
    val models: List<CatalogModel>,
) {
    val label: String get() = name.takeIf { it.isNotBlank() } ?: id
}

/** 拉取失败的 provider：仍然要显示，否则用户会以为模型「凭空消失」。 */
data class CatalogFailure(val id: String, val name: String, val message: String)

data class ModelCatalog(
    /** 未配置过的会话默认用哪一个；Host 没给就为 null。 */
    val default: ModelSelection?,
    /**
     * 至少有一个可用模型的 provider。
     *
     * 契约里 `routableProviders` 是**必填**数组（Host 只返回有可用模型的 provider），
     * 因此空数组是有效值：表示当前没有任何可路由的 provider，此时所有模型都必须
     * 置灰。只有 null 才代表「Host 没下发这个字段」（旧版兼容），按未知处理、不据
     * 此禁用——否则一个字段缺失就会让整份目录看起来不可用。
     */
    val routable: Set<String>?,
    val groups: List<ModelGroup>,
    val failures: List<CatalogFailure>,
) {
    val models: List<Pair<ModelGroup, CatalogModel>>
        get() = groups.flatMap { group -> group.models.map { group to it } }

    /**
     * 某个 provider 现在是否可路由。
     *
     * 名单缺席时放行（交回 Host，失败会有明确回包），名单存在时严格按名单判定，
     * 空名单意味着全部不可路由。
     */
    fun isRoutable(provider: String): Boolean = routable?.contains(provider) ?: true

    fun find(provider: String, model: String): CatalogModel? =
        groups.firstOrNull { it.id == provider }?.models?.firstOrNull { it.id == model }

    fun groupOf(provider: String): ModelGroup? = groups.firstOrNull { it.id == provider }

    val isEmpty: Boolean get() = groups.all { it.models.isEmpty() }
}

/** 解析 `{ provider, model, reasoningEffort? }`；缺关键字段就认为没给。 */
internal fun parseSelection(value: JsonObject?): ModelSelection? {
    value ?: return null
    val provider = value.str("provider")?.takeIf { it.isNotBlank() } ?: return null
    val model = value.str("model")?.takeIf { it.isNotBlank() } ?: return null
    return ModelSelection(provider, model, value.str("reasoningEffort")?.takeIf { it.isNotBlank() })
}

/**
 * 解析 `modelSelection` 投影。
 *
 * 投影的形状是 `{ lastUsed, next }`（`next` 是下一次请求要用的，用户刚切换但还没
 * 发出消息时它会领先于 `lastUsed`）。这里优先 `next`，同时也兼容直接给一个
 * selection 的老形状，避免 Host 版本差异导致整块模型信息消失。
 */
internal fun parseSelectionProjection(value: JsonObject?): ModelSelection? {
    value ?: return null
    parseSelection(value.obj("next"))?.let { return it }
    parseSelection(value.obj("lastUsed"))?.let { return it }
    return parseSelection(value)
}

private fun localizedBadge(badge: JsonObject?): String? {
    badge ?: return null
    badge.str("text")?.takeIf { it.isNotBlank() }?.let { return it }
    val text = badge.obj("text") ?: return null
    return listOf("zh-CN", "zh-Hans", "zh-cn", "zh-hans", "zh", "en")
        .firstNotNullOfOrNull { text.str(it)?.takeIf(String::isNotBlank) }
}

/** 解析 `session/modelCatalog` 的返回值。任何一段坏数据都跳过，不让整份目录失效。 */
internal fun parseModelCatalog(value: JsonObject): ModelCatalog {
    val groups = value.arr("groups").mapNotNull { raw ->
        val group = raw as? JsonObject ?: return@mapNotNull null
        val id = group.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val models = group.arr("models").mapNotNull { entry ->
            val model = entry as? JsonObject ?: return@mapNotNull null
            val modelId = model.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val reasoning = model.obj("reasoning")
            CatalogModel(
                id = modelId,
                name = model.str("name").orEmpty(),
                description = model.str("description")?.takeIf { it.isNotBlank() },
                badge = localizedBadge(model.obj("badge")) ?: model.str("badge")?.takeIf { it.isNotBlank() },
                badgeTone = model.obj("badge")?.str("tone"),
                efforts = reasoning?.arr("efforts").orEmpty().mapNotNull { effortRaw ->
                    val effort = effortRaw as? JsonObject ?: return@mapNotNull null
                    val effortId = effort.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ModelEffort(effortId, effort.str("name")?.takeIf { it.isNotBlank() } ?: effortId,
                        effort.str("description")?.takeIf { it.isNotBlank() })
                },
                defaultEffort = reasoning?.str("defaultEffort")?.takeIf { it.isNotBlank() },
            )
        }
        ModelGroup(id = id, name = group.str("name").orEmpty(), models = models)
    }
    val failures = value.arr("failures").mapNotNull { raw ->
        val failure = raw as? JsonObject ?: return@mapNotNull null
        val id = failure.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CatalogFailure(
            id = id,
            name = failure.str("name")?.takeIf { it.isNotBlank() } ?: id,
            message = failure.str("message").orEmpty(),
        )
    }
    return ModelCatalog(
        default = parseSelection(value.obj("default")),
        // 字段缺失（null）与显式空数组必须区分：前者是旧版 Host 没给，按未知放行；
        // 后者按契约就是「零个可路由 provider」，必须禁用全部模型。
        routable = value["routableProviders"]?.let { element ->
            (element as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.toSet()
        },
        groups = groups,
        failures = failures,
    )
}
