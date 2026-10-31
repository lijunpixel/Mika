package com.wangzan101.mika.ui

import android.graphics.Bitmap
import android.app.UiAutomation
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Presentation fixtures use production UI with isolated temporary state; no MikaApp or user conversation is used. */
@RunWith(AndroidJUnit4::class)
class BrandingScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var fixtureScope: CoroutineScope? = null
    private var fixtureStoreDir: File? = null

    private fun fixtureViewModel(): ChatViewModel {
        fixtureStoreDir = File(context.cacheDir, "branding-chat-fixture-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fixtureScope = scope
        val forbidden = object : TaskActions {
            private fun fail(action: String): Nothing = error("Branding fixture unexpectedly invoked TaskActions.$action")
            override fun start(goal: String, conversationId: String) = fail("start")
            override fun steer(conversationId: String, messageId: String, text: String) = fail("steer")
            override fun cancel() = fail("cancel")
            override fun resume() = fail("resume")
            override fun takeOver() = fail("takeOver")
            override fun handBack() = fail("handBack")
            override fun finishedMyself() = fail("finishedMyself")
            override fun skip() = fail("skip")
            override fun answer(text: String) = fail("answer")
            override fun approve(key: Int) = fail("approve")
            override fun decline(key: Int) = fail("decline")
            override fun show(conversationId: String) = fail("show")
            override fun cancelIn(conversationId: String) = fail("cancelIn")
            override fun resumeIn(conversationId: String) = fail("resumeIn")
            override fun takeOverIn(conversationId: String) = fail("takeOverIn")
            override fun handBackIn(conversationId: String) = fail("handBackIn")
            override fun finishedMyselfIn(conversationId: String) = fail("finishedMyselfIn")
            override fun skipIn(conversationId: String) = fail("skipIn")
            override fun answerIn(conversationId: String, text: String) = fail("answerIn")
            override fun approveIn(conversationId: String, key: Int) = fail("approveIn")
            override fun declineIn(conversationId: String, key: Int) = fail("declineIn")
        }
        return ChatViewModel(
            scope = scope,
            taskState = MutableStateFlow(TaskState.Idle),
            takeoverStage = MutableStateFlow(Takeover.Stage.None),
            actions = forbidden,
            store = ConversationStore(fixtureStoreDir!!),
        )
    }

    @After
    fun disposeFixture() {
        fixtureScope?.cancel()
        fixtureScope = null
        fixtureStoreDir?.let { directory ->
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile &&
                directory.name.matches(Regex("branding-chat-fixture-[0-9a-f-]{36}")))
            directory.deleteRecursively()
        }
        fixtureStoreDir = null
    }

    private fun saveFixtureShot(name: String): String {
        compose.waitForIdle()
        val folder = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
        val file = File(folder, name)
        val image = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        Log.i("BrandingScreenTest", "fixture screenshot: ${file.absolutePath}")
        return file.absolutePath
    }

    /** Full production ChatScreen with an isolated empty store and fail-fast task actions; examples are never tapped. */
    @Test
    fun brandingFixtureProductionChatTopAndSixExamples() {
        val vm = fixtureViewModel()
        compose.setContent {
            MikaTheme {
                ChatScreen(
                    vm = vm,
                    check = Prerequisites.Check(emptyList()),
                    frames = MutableStateFlow(null),
                    webOpen = MutableStateFlow(false),
                    reduceMotion = true,
                    onOpenDrawer = {},
                    openSettings = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(ChatCopy.APP_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ChatCopy.EMPTY_PROMPT).assertIsDisplayed()
        saveFixtureShot("branding-fixture-chat-top-upper.png")
        ChatCopy.examples.forEachIndexed { index, example ->
            compose.onNodeWithText(example.text).performScrollTo().assertIsDisplayed()
            if (index == ChatCopy.examples.lastIndex) saveFixtureShot("branding-fixture-chat-examples-lower.png")
        }
    }

    /** Renders the production sidebar composable with empty data; no stored conversations are touched. */
    @Test
    fun brandingFixtureSidebar() {
        compose.setContent {
            MikaTheme {
                ChatDrawer(
                    check = Prerequisites.Check(emptyList()),
                    sections = DrawerSections(emptyList(), emptyList()),
                    canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {}, onBrowser = {}, onSettings = {}, onOpen = {},
                )
            }
        }
        compose.onNodeWithText("ibot").assertIsDisplayed()
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.TITLE).assertIsDisplayed()
        saveFixtureShot("branding-fixture-sidebar.png")
    }
}
