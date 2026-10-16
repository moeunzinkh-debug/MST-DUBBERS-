package com.example

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.SettingsRepository
import com.example.data.db.AppDatabase
import com.example.data.db.ProjectEntity
import com.example.data.db.SegmentEntity
import com.example.media.BlurRegion
import com.example.media.BlurSettings
import com.example.ui.DubberViewModel
import com.example.ui.screens.EditorScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Opens the editor on a real project (portrait checkerboard video + Khmer lines) and checks the
 * player controls, tap-to-seek while playing, the subtitle toggle and the blur box gestures.
 */
@RunWith(AndroidJUnit4::class)
class EditorScreenUiTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as Application
  private val database = AppDatabase.getInstance(app)
  private var projectId = 0L
  private val segmentIds = mutableListOf<Long>()

  @Before
  fun setUp() = runBlocking {
    val video = File(app.filesDir, "projects/ui_test_video.mp4").apply { parentFile?.mkdirs() }
    instrumentation.context.assets.open("test_video_rotated.mp4").use { input ->
      video.outputStream().use { input.copyTo(it) }
    }
    projectId = database.projectDao().insertProject(
      ProjectEntity(name = "UI test", videoPath = video.absolutePath, duration = 0.0)
    )
    val lines = listOf(
      Triple(0.2, 1.4, "ខ្ញុំដូចជាត្រូវពួកអាជ្ញាធរចាប់អារម្មណ៍ហើយ ថ្ងៃនេះយើងត្រូវតែចេញទៅ។"),
      Triple(1.5, 2.4, "បើអ្នកទាំងពីរមានផ្លូវទៅល្អជាងនេះ ក៏អាចបែកផ្លូវពីខ្ញុំបានដែរ។"),
      Triple(2.6, 3.4, "ឥឡូវនេះ មានកន្លែងណាសុវត្ថិភាពជាងនៅទីនេះទៀត?"),
      Triple(3.5, 3.9, "តោះទៅ!")
    )
    database.segmentDao().insertSegments(
      lines.mapIndexed { index, (start, end, khmer) ->
        SegmentEntity(
          projectId = projectId,
          idx = index + 1,
          start = start,
          end = end,
          text = "Original line ${index + 1}",
          translation = khmer,
          gender = if (index % 2 == 0) "female" else "male"
        )
      }
    )
    segmentIds += database.segmentDao().getSegmentsForProject(projectId).sortedBy { it.idx }.map { it.id }
    SettingsRepository(app).saveBlurSettings(
      projectId,
      BlurSettings(enabled = true, region = BlurRegion(0.1f, 0.62f, 0.9f, 0.8f))
    )
  }

  @After
  fun tearDown() = runBlocking {
    database.projectDao().deleteProjectById(projectId)
    SettingsRepository(app).clearBlurSettings(projectId)
  }

  @Test
  fun editorControlsSeekSubtitlesAndBlurBox() {
    val viewModel = DubberViewModel(app)
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        EditorScreen(projectId = projectId, viewModel = viewModel, onNavigateBack = {})
      }
    }

    // The player is ready once it reports the video duration.
    composeRule.waitUntil(15_000) { viewModel.duration.value > 3.0 }
    Thread.sleep(1_200) // first frame + blur preview snapshot
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("blur_box", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithTag("player_subtitles_button").assertExists()
    logScreenshot("editor_paused")

    // Tap a line while playing: the video must jump there and keep playing.
    composeRule.onNodeWithTag("player_play_pause_button").performClick()
    composeRule.waitUntil(5_000) { viewModel.isPlaying.value && viewModel.currentTime.value > 0.2 }
    // Tap on the text of the line (not on its chips/buttons).
    tapLine(segmentIds[2])
    Thread.sleep(700)
    composeRule.waitForIdle()
    val afterSeek = viewModel.currentTime.value
    assertTrue("position reported by the player after tapping line 3: $afterSeek", afterSeek >= 2.6 && afterSeek < 3.9)
    logScreenshot("editor_playing")
    composeRule.onNodeWithTag("player_play_pause_button").performClick()
    composeRule.waitUntil(3_000) { !viewModel.isPlaying.value }

    // Tapping the first line while paused moves back to it.
    tapLine(segmentIds[0])
    composeRule.waitForIdle()
    assertEquals(0.25, viewModel.currentTime.value, 0.01)

    // The CC button hides and shows the subtitles.
    val subtitlesBefore = viewModel.settings.value.showSubtitles
    composeRule.onNodeWithTag("player_subtitles_button").performClick()
    composeRule.waitForIdle()
    assertEquals(!subtitlesBefore, viewModel.settings.value.showSubtitles)
    composeRule.onNodeWithTag("player_subtitles_button").performClick()
    composeRule.waitForIdle()
    assertEquals(subtitlesBefore, viewModel.settings.value.showSubtitles)

    // Drag the blur box up, then make it bigger from its bottom-right corner.
    val before = viewModel.blurSettings.value.region
    composeRule.onNodeWithTag("blur_box", useUnmergedTree = true).performTouchInput {
      swipe(start = center, end = center + Offset(0f, -60f), durationMillis = 400)
    }
    composeRule.waitForIdle()
    val moved = viewModel.blurSettings.value.region
    assertTrue("box moved up: $before -> $moved", moved.top < before.top - 0.02f)
    assertEquals(before.width, moved.width, 0.001f)

    composeRule.onNodeWithTag("blur_handle_bottom_right", useUnmergedTree = true).performTouchInput {
      swipe(start = center, end = center + Offset(-40f, 50f), durationMillis = 400)
    }
    composeRule.waitForIdle()
    val resized = viewModel.blurSettings.value.region
    assertTrue("box resized: $moved -> $resized", resized.height > moved.height + 0.02f && resized.width < moved.width)

    // The new box is saved for the project.
    assertEquals(resized, SettingsRepository(app).loadBlurSettings(projectId).region)
    Thread.sleep(500)
    logScreenshot("editor_blur_moved")
  }

  private fun tapLine(segmentId: Long) {
    val tag = "segment_row_$segmentId"
    composeRule.onNodeWithTag("segment_list").performScrollToNode(hasTestTag(tag))
    composeRule.onNodeWithTag(tag).performTouchInput { click(percentOffset(0.6f, 0.9f)) }
  }

  /** Logs a small JPEG of the screen as base64, so CI can show it without downloading artifacts. */
  private fun logScreenshot(name: String) {
    val captured = instrumentation.uiAutomation.takeScreenshot() ?: return
    val screen = if (Build.VERSION.SDK_INT >= 26 && captured.config == Bitmap.Config.HARDWARE) {
      captured.copy(Bitmap.Config.ARGB_8888, false)
    } else {
      captured
    }
    val width = 300
    val height = (screen.height * width.toFloat() / screen.width).toInt()
    val small = Bitmap.createScaledBitmap(screen, width, height, true)
    val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 50, it) }.toByteArray()
    val chunks = Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(3000)
    chunks.forEachIndexed { index, chunk -> Log.i("MSTSHOT", "$name ${index + 1}/${chunks.size} $chunk") }
  }
}
