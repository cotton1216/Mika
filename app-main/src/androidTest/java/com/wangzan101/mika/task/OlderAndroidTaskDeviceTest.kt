package com.wangzan101.mika.task

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 安卓 11:不操作手机的任务不需要内置助手和无障碍服务。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30, maxSdkVersion = 30)
class OlderAndroidTaskDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = ScriptedModel().apply { intent = "ACT" }

    @Before
    fun setUp() {
        assertNotNull(pollUntil(10_000) { true.takeIf { !DeviceTaskState.busy.value } })
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { true.takeIf { !DeviceTaskState.busy.value } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun start(goal: String = "Reply with done") {
        TaskService.start(context, goal, "older-android-${UUID.randomUUID()}")
        assertEquals(1, model.awaitCall().number)
    }

    private fun finish() {
        model.answer("done")
        val end = pollUntil(15_000) {
            DeviceTaskState.state.value.takeIf {
                it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled
            }
        }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "done")
        assertEquals(-1, testTasks.agentDisplayId.value)
    }

    @Test
    fun textTaskCompletesWithoutPhoneControl() {
        start()
        finish()
    }

    @Test
    fun phoneControlReportsVersionRequirementAndTaskCanContinue() {
        start("Open Android settings")
        model.feed("phone_launch_app", """{"package":"com.android.settings"}""")
        val result = model.awaitCall().messages.last().toString()
        assertTrue(result, "phone_unavailable" in result)
        assertTrue(result, TaskService.TOO_OLD in result)
        assertEquals(-1, testTasks.agentDisplayId.value)
        assertFalse(testTasks.webScreenOpen.value)
        finish()
    }
}
