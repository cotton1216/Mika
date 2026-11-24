package com.wangzan101.mika.selfcheck

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.selfcheck.CheckStatus
import com.wangzan101.mika.core.selfcheck.SelfCheckReport
import com.wangzan101.mika.core.selfcheck.SelfCheckRunner
import com.wangzan101.mika.core.selfcheck.SelfCheckState
import com.wangzan101.mika.settings.SystemStatusMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机:把兼容性自检完整跑一遍,和设置里点「开始自检」走的是同一个 [AndroidSelfCheck]。
 * 要内置助手在运行、Mika 的无障碍开着;报告打在 logcat(标签 MikaSelfCheck)。默认只要求跑完、每项都有结论;
 * 带 `-e selfcheck.strict true` 时还要求没有一项失败。
 */
@RunWith(AndroidJUnit4::class)
class SelfCheckDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val strict = InstrumentationRegistry.getArguments().getString("selfcheck.strict") == "true"

    @Test
    fun wholeSelfCheckCompletes() = runBlocking<Unit> {
        // 和别的真机测试一样先占住共用的助手连接、促使无障碍重绑(启动测试时系统会强停 App)
        DeviceSupport.shell()
        DeviceSupport.service()
        val monitor = SystemStatusMonitor(context, PhoneServices.of(context).shellControl)
        val runner = SelfCheckRunner(AndroidSelfCheck(context) { monitor.refresh(); monitor.status.value })
        val s = withContext(Dispatchers.Default) { runner.run() }
        val report = SelfCheckReport.render(s, Diagnostics::sanitize)
        report.lines().forEach { Log.i(TAG, it) }

        assertEquals(SelfCheckState.Phase.Done, s.phase)
        assertTrue(report, s.results.all { it.status.final })
        assertTrue(report, s.elapsedMs < SelfCheckRunner.TOTAL_BUDGET_MS + 30_000)
        if (strict) assertTrue(report, s.results.none { it.status == CheckStatus.Fail })
    }

    private companion object {
        const val TAG = "MikaSelfCheck"
    }
}
