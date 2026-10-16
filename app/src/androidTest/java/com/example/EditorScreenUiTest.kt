package com.example

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.SettingsRepository
import com.example.data.db.AppDatabase
import com.example.data.db.ProjectEntity
import com.example.data.db.SegmentEntity
import com.example.media.BlurRegion
import com.example.media.BlurSettings
import com.example.media.BlurStrength
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
 * player controls, tap-to-seek while playing, the subtitle toggle, the blur box gestures and the
 * blur strength panel (long press on the blur icon).
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
    // Like MainActivity: draw behind the system bars.
    composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
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
    // Paused right after opening, the picture must already show the video (not stay black).
    val contrast = pictureContrast()
    assertTrue("video frame visible while paused (luma std dev $contrast)", contrast > 40.0)
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

    // Holding the blur icon opens the strength panel below the player.
    composeRule.onNodeWithTag("editor_blur_button").performTouchInput { longClick() }
    composeRule.waitUntil(3_000) { composeRule.onAllNodesWithTag("blur_strength_panel").fetchSemanticsNodes().isNotEmpty() }
    assertTrue("a long press doesn't turn the blur off", viewModel.blurSettings.value.enabled)

    // Weak keeps the checkerboard visible under the box; Strong smooths it out (live preview).
    composeRule.onNodeWithTag("blur_preset_weak").performClick()
    composeRule.waitForIdle()
    assertEquals(BlurStrength.WEAK, viewModel.blurSettings.value.strength, 0.001f)
    Thread.sleep(400)
    val weakContrast = blurBoxContrast()
    composeRule.onNodeWithTag("blur_preset_strong").performClick()
    composeRule.waitForIdle()
    assertEquals(BlurStrength.STRONG, viewModel.blurSettings.value.strength, 0.001f)
    composeRule.onNodeWithTag("blur_strength_value").assertTextEquals("Strong · 90%")
    Thread.sleep(400)
    val strongContrast = blurBoxContrast()
    assertTrue("strong blur is smoother than weak (luma std dev $strongContrast vs $weakContrast)",
      weakContrast > 20.0 && strongContrast < weakContrast * 0.5)
    assertEquals(BlurStrength.STRONG, SettingsRepository(app).loadBlurSettings(projectId).strength, 0.001f)
    logScreenshot("editor_blur_strength", left = 0.2f, top = 0.04f, bottom = 0.72f, width = 280, quality = 45)

    // The slider sets any strength in between.
    composeRule.onNodeWithTag("blur_strength_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
    composeRule.waitForIdle()
    assertEquals(0.3f, viewModel.blurSettings.value.strength, 0.02f)
    composeRule.onNodeWithTag("blur_strength_value").assertTextEquals("Weak · 30%")

    // Back closes the panel and the strength is kept for the project.
    Espresso.pressBack()
    composeRule.waitUntil(3_000) { composeRule.onAllNodesWithTag("blur_strength_panel").fetchSemanticsNodes().isEmpty() }
    assertEquals(0.3f, SettingsRepository(app).loadBlurSettings(projectId).strength, 0.02f)
  }

  /**
   * Luma standard deviation inside the upper part of the blur box: away from its dashed frame,
   * its handles and the subtitle shown at the bottom of the picture.
   */
  private fun blurBoxContrast(): Double {
    val bounds = composeRule.onNodeWithTag("blur_box", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    return lumaStdDev(
      bounds.left + bounds.width * 0.2f,
      bounds.top + bounds.height * 0.15f,
      bounds.right - bounds.width * 0.2f,
      bounds.top + bounds.height * 0.55f
    )
  }

  private fun tapLine(segmentId: Long) {
    val tag = "segment_row_$segmentId"
    composeRule.onNodeWithTag("segment_list").performScrollToNode(hasTestTag(tag))
    composeRule.onNodeWithTag(tag).performTouchInput { click(percentOffset(0.6f, 0.9f)) }
  }

  /** Luma standard deviation in the upper middle of the picture (away from the blur box). */
  private fun pictureContrast(): Double {
    val bounds = composeRule.onNodeWithTag("video_picture_area").fetchSemanticsNode().boundsInRoot
    return lumaStdDev(
      bounds.left + bounds.width * 0.45f,
      bounds.top + bounds.height * 0.10f,
      bounds.left + bounds.width * 0.55f,
      bounds.top + bounds.height * 0.40f
    )
  }

  /** Luma standard deviation of the screen inside the given rectangle (screen pixels). */
  private fun lumaStdDev(left: Float, top: Float, right: Float, bottom: Float): Double {
    val screen = screenshot() ?: return 0.0
    val x0 = left.toInt().coerceIn(0, screen.width - 1)
    val x1 = right.toInt().coerceIn(x0 + 1, screen.width)
    val y0 = top.toInt().coerceIn(0, screen.height - 1)
    val y1 = bottom.toInt().coerceIn(y0 + 1, screen.height)
    var sum = 0.0
    var sumSq = 0.0
    var n = 0
    for (y in y0 until y1) {
      for (x in x0 until x1) {
        val c = screen.getPixel(x, y)
        val luma = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
        sum += luma
        sumSq += luma * luma
        n++
      }
    }
    val mean = sum / n
    return kotlin.math.sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
  }

  private fun screenshot(): Bitmap? {
    val captured = instrumentation.uiAutomation.takeScreenshot() ?: return null
    return if (Build.VERSION.SDK_INT >= 26 && captured.config == Bitmap.Config.HARDWARE) {
      captured.copy(Bitmap.Config.ARGB_8888, false)
    } else {
      captured
    }
  }

  /** Logs a small JPEG of the screen as base64, so CI can show it without downloading artifacts. */
  private fun logScreenshot(
    name: String,
    left: Float = 0f,
    top: Float = 0f,
    right: Float = 1f,
    bottom: Float = 1f,
    width: Int = 300,
    quality: Int = 50
  ) {
    val screen = screenshot() ?: return
    val x0 = (screen.width * left).toInt()
    val y0 = (screen.height * top).toInt()
    val part = Bitmap.createBitmap(
      screen, x0, y0,
      (screen.width * right).toInt() - x0,
      (screen.height * bottom).toInt() - y0
    )
    val height = (part.height * width.toFloat() / part.width).toInt()
    val small = Bitmap.createScaledBitmap(part, width, height, true)
    val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    val chunks = Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(3000)
    chunks.forEachIndexed { index, chunk -> Log.i("MSTSHOT", "$name ${index + 1}/${chunks.size} $chunk") }
  }
}
