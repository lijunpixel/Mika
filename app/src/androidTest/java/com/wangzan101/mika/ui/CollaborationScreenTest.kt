package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.collaboration.CollaborationRuntime
import com.wangzan101.mika.core.collaboration.CollaborationMode
import com.wangzan101.mika.core.collaboration.CollaborationStatus
import com.wangzan101.mika.core.collaboration.ConnectionKind
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollaborationScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private fun runtime(mockLatencyMillis: Long = 0): CollaborationRuntime {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MikaApp
        return CollaborationRuntime(context, app.providers, mockLatencyMillis)
    }

    @Test fun unconfiguredRealTaskCannotStart() {
        val runtime = runtime()
        compose.setContent { MikaTheme { CollaborationScreen(runtime, {}, {}) } }
        compose.onNodeWithTag("ibot_collaboration").assertIsDisplayed()
        compose.onNodeWithTag("ibot_goal").performScrollTo().performTextInput("check configuration")
        compose.onNodeWithTag("ibot_start").performScrollTo().assertIsNotEnabled()
    }

    @Test fun allFourMockModesStartAndComplete() {
        val runtime = runtime()
        compose.setContent { MikaTheme { CollaborationScreen(runtime, {}, {}) } }
        compose.onNodeWithTag("ibot_mock_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ibot_goal").performScrollTo().performTextInput("Verify a deterministic local result")
        compose.waitUntil(5_000) { runtime.agents.value.any { it.connectionKind == ConnectionKind.Mock } }
        compose.waitForIdle()
        CollaborationMode.entries.forEach { mode ->
            compose.onNodeWithTag("ibot_mode_${mode.name}").performScrollTo().assertIsDisplayed().performClick()
            val priorIds = runtime.runs.value.map { it.id }.toSet()
            compose.onNodeWithTag("ibot_start").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) {
                runtime.runs.value.firstOrNull { it.id !in priorIds && it.request.mode == mode }
                    ?.status?.let { it !in setOf(CollaborationStatus.Queued, CollaborationStatus.Running) } == true
            }
            val run = runtime.runs.value.first { it.id !in priorIds && it.request.mode == mode }
            org.junit.Assert.assertTrue(run.status in setOf(CollaborationStatus.Succeeded, CollaborationStatus.PartialSuccess))
            compose.onNodeWithTag("ibot_result").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun activeSlowMockTaskCanBeCancelled() {
        val runtime = runtime(mockLatencyMillis = 30_000)
        compose.setContent { MikaTheme { CollaborationScreen(runtime, {}, {}) } }
        compose.onNodeWithTag("ibot_mock_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ibot_goal").performScrollTo().performTextInput("Cancel a deliberately slow Mock task")
        compose.waitUntil(5_000) { runtime.agents.value.any { it.connectionKind == ConnectionKind.Mock } }
        compose.waitForIdle()
        val priorIds = runtime.runs.value.map { it.id }.toSet()
        compose.onNodeWithTag("ibot_start").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            runtime.runs.value.firstOrNull { it.id !in priorIds }?.status == CollaborationStatus.Running
        }
        val run = runtime.runs.value.first { it.id !in priorIds }
        compose.onNodeWithTag("ibot_cancel").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { runtime.get(run.id)?.status == CollaborationStatus.Cancelled }
    }
}
