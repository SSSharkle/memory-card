package cn.flashcard.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.flashcard.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Executes on ART/ICU, covering behavior desktop JVM tests cannot prove. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun androidIcuClozeAndMarkdownParser() {
        assertEquals(listOf(1, 2), Cloze.indices("{{c1::甲}} {{c1::乙}} {{c2::丙::提示}}"))
        assertEquals("【…】 【…】 丙", Cloze.render("{{c1::甲}} {{c1::乙}} {{c2::丙}}", 1, false))
        val preview =
            MarkdownImport.parse(
                "---\nsubject: \"408\"\ndeck: \"操作系统\"\ntags: [\"测试\"]\n---\n## 卡\n### 正面\n{{c1::答案}}\n### 背面\n说明"
            )
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertEquals(1, preview.cardCount)
    }

    @Test
    fun launcherOfflineTodayHasChineseLabels() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("把知识留在记忆里").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("把知识留在记忆里").assertIsDisplayed()
            compose.onNodeWithText("牌组").assertIsDisplayed()
            compose.onNodeWithText("设置").assertIsDisplayed()
        }
    }

    @Test
    fun statusBarIconsFollowSelectedTheme() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ref = java.util.concurrent.atomic.AtomicReference<AppModel>()
            scenario.onActivity {
                ref.set(androidx.lifecycle.ViewModelProvider(it)[AppModel::class.java])
            }
            val model = ref.get()
            compose.waitUntil(10000) { model.ready.value }
            val original = kotlinx.coroutines.runBlocking { model.repo.dao.preference()!!.value }
            try {
                for (theme in listOf("dark", "light")) {
                    kotlinx.coroutines.runBlocking {
                        model.repo.saveSettings(original.copy(theme = theme))
                    }
                    compose.waitUntil(10000) { model.settings.value.theme == theme }
                    compose.waitForIdle()
                    scenario.onActivity { activity ->
                        val controller =
                            androidx.core.view.WindowCompat.getInsetsController(
                                activity.window,
                                activity.window.decorView,
                            )
                        assertEquals(theme == "light", controller.isAppearanceLightStatusBars)
                        assertEquals(theme == "light", controller.isAppearanceLightNavigationBars)
                    }
                }
            } finally {
                kotlinx.coroutines.runBlocking { model.repo.saveSettings(original) }
            }
        }
    }
}
