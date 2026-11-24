package com.wangzan101.mika.task

/**
 * 网页界面时画中画小窗显示什么(纯逻辑,[TaskRun] 每半秒按它更新)。
 *
 * 小窗的画面来自网页显示上挂着的当前标签。只在后台临时标签里读网页时(`web_read_page` 带地址),
 * 网页显示上没有标签,画出来只是一块白底:这时不算在网页上,小窗不出来,悬浮胶囊也按对话卡片显示;
 * 一打开看得见的页面就照常显示。页面在用户手上(接管中)时仍算在网页上,只是不放画面。
 */
enum class WebPreview(val open: Boolean) {
    /** 不在网页上:不算在网页上,画面不动(换界面时已清掉)。 */
    Off(false),

    /** 在网页上、却没有看得见的标签(只在后台临时标签里读):不算在网页上,清掉画面。 */
    NoPage(false),

    /** 在网页上、页面在用户手上:算在网页上,不放画面。 */
    Hidden(true),

    /** 在网页上、有看得见的页面:放最新一帧。 */
    Frames(true);

    companion object {
        fun of(onWeb: Boolean, hasTab: Boolean, handedOver: Boolean): WebPreview = when {
            !onWeb -> Off
            !hasTab -> NoPage
            handedOver -> Hidden
            else -> Frames
        }
    }
}
