package com.wangzan101.mika.cap.phone.a11y

import android.app.UiAutomation
import android.graphics.Path
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/** 与 uiautomator dump 一样压制再恢复服务,保留任务原来拿到的实例和事件订阅。 */
@RunWith(AndroidJUnit4::class)
class AccessibilityReconnectDeviceTest {
    @Test fun firstNotificationAfterSubscribingIsNotLost() = runBlocking<Unit> {
        val service = DeviceSupport.service()
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { service.notificationEvents().first { it.packageName == PROBE_PACKAGE } }
        }
        try {
            service.onAccessibilityEvent(notification("first"))
            assertEquals("first", event.await().text)
        } finally { event.cancel() }
    }

    @Test fun oldCallerReadsActsAndReceivesEventsAfterReconnect() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val original = DeviceSupport.service()
        val testPackage = instrumentation.context.packageName
        fun showPage() = DeviceSupport.sh("am start -n $testPackage/${PlainTestActivity::class.java.name}")
        fun pageVisible() = original.windowsOn(Display.DEFAULT_DISPLAY).any { it.root?.packageName?.toString() == testPackage }
        showPage()
        assertNotNull("initial page", DeviceSupport.pollUntil(5_000) { true.takeIf { pageVisible() } })
        val afterReconnect = AtomicLong(Long.MAX_VALUE)
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(20_000) {
                original.eventsFor(Display.DEFAULT_DISPLAY).first { it.eventTime >= afterReconnect.get() }
            }
        }
        try {
            instrumentation.getUiAutomation(0)
            withTimeout(5_000) { ScreenAccessibility.instance.first { it == null } }
            assertTrue("disconnected windows", original.windowsOn(Display.DEFAULT_DISPLAY).isEmpty())
            assertFalse("no gesture while disconnected", original.gesture(Display.DEFAULT_DISPLAY, Path().apply { moveTo(10f, 10f) }, 50))

            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val replacement = withTimeout(10_000) { ScreenAccessibility.instance.filterNotNull().first { it !== original } }
            assertNotSame(original, replacement)
            assertNotNull("old caller must see the new service's windows", DeviceSupport.pollUntil(5_000) { true.takeIf { pageVisible() } })
            assertTrue("old caller must dispatch through the new service", original.gesture(Display.DEFAULT_DISPLAY, Path().apply { moveTo(10f, 200f) }, 50))
            afterReconnect.set(SystemClock.uptimeMillis())
            DeviceSupport.sh("input keyevent 4")
            showPage()
            assertNotNull("existing event subscription must follow the new service", event.await())
            val notification = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) { original.notificationEvents().first { it.packageName == PROBE_PACKAGE } }
            }
            try {
                original.onAccessibilityEvent(notification("stale"))
                replacement.onAccessibilityEvent(notification("fresh"))
                assertEquals("ignore callbacks from the disconnected instance", "fresh", notification.await().text)
            } finally { notification.cancel() }
        } finally {
            event.cancel()
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            DeviceSupport.sh("input keyevent 4")
        }
    }

    @Suppress("DEPRECATION")
    private fun notification(text: String) = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED).apply {
        packageName = PROBE_PACKAGE
        this.text.add(text)
    }

    private companion object { const val PROBE_PACKAGE = "mika.test.reconnect" }
}
