package com.wangzan101.mika.capsule

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/** 卡片上的小画面每秒最多几张。 */
const val PREVIEW_FPS = 5

/**
 * 卡片小画面要推的帧:每 [intervalMs] 至多一张,中间来的只留最新的;和上一张推过的一样([same])就不推。
 * 只在卡片展开时收,收起时取消,不收就不推。
 */
fun <T : Any> Flow<T>.previewFrames(intervalMs: Long = 1_000L / PREVIEW_FPS, same: (T, T) -> Boolean = { a, b -> a === b }): Flow<T> = flow {
    var last: T? = null
    conflate().collect { f ->
        val prev = last
        if (prev != null && (prev === f || same(prev, f))) return@collect
        last = f
        emit(f)
        delay(intervalMs)
    }
}

/**
 * 新的一帧和上一张一样(同一张图,或尺寸相同、像素都相同)就不推:网页画面每次给的是新拷贝。
 * 虚拟屏那边旧帧会被回收,所以先看 [Bitmap.isRecycled],别拿回收了的图去比像素。
 */
fun sameFrame(a: Bitmap, b: Bitmap): Boolean =
    a === b || !a.isRecycled && !b.isRecycled && a.width == b.width && a.height == b.height && a.sameAs(b)
