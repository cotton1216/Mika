package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.settings.AppAccessStore

/**
 * 用户主动分享被禁用应用的屏幕时,浮层上的三个选择对应什么:
 * 1 = 允许仅分享屏幕(以后可以主动分享,自动读取和操作仍禁止),2 = 允许此应用(恢复正常调用),0/其他 = 取消分享。
 * 返回是否继续这次分享;取消时不动权限。选「允许仅分享」不会解除禁用,选「允许此应用」会清掉独立分享许可。
 */
internal fun applyShareChoice(choice: Int, pkg: String, store: AppAccessStore): Boolean = when (choice) {
    1 -> { store.setShareAllowed(pkg, true); true }
    2 -> { store.setBlocked(pkg, false); true }
    else -> false
}
