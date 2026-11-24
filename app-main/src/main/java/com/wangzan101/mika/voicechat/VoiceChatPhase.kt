package com.wangzan101.mika.voicechat

/** 实时语音的阶段:连上之后就一直双向通话,它说的时候你也能直接开口打断。 */
enum class VoiceChatPhase {
    /** 正在连实时语音、开麦克风 */
    STARTING,
    /** 连着:你随时可以说,它随时会答 */
    LISTENING,
    /** 它正在说 */
    SPEAKING,
    /** 已结束 */
    ENDED,
}

enum class VoiceChatEnd {
    /** 用户点了挂断 */
    USER_HUNG_UP,
    /** 麦克风权限没拿到 */
    PERMISSION_DENIED,
    /** 还没配豆包实时语音凭据 */
    NOT_CONFIGURED,
    /** 连不上实时语音(网络、鉴权、额度等) */
    CONNECT_FAILED,
    /** 手机音频打不开 */
    AUDIO_FAILED,
}

data class VoiceChatState(
    val phase: VoiceChatPhase = VoiceChatPhase.STARTING,
    /** 它最近一句说出口的话(边收边拼) */
    val lastAssistantReply: String = "",
    /** 你此刻正在说的话(实时识别) */
    val partial: String = "",
    val endReason: VoiceChatEnd? = null,
    /** 异常时的补充说明(给用户看的一句话) */
    val errorDetail: String? = null,
    /** 它正在说 */
    val aiSpeaking: Boolean = false,
    /** 麦克风开着、没静音 */
    val userSpeaking: Boolean = false,
)
