package com.example

import com.example.media.BlurStrength
import com.example.media.PreviewBlur
import com.example.media.RegionBlurEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.random.Random

class BlurStrengthTest {

  private fun sigma(strength: Float) = BlurStrength.sigmaFraction(strength)

  @Test
  fun `medium is the blur used before and the strength grows steadily`() {
    // Projects saved before the strength setting keep the same look.
    assertEquals(0.025f, sigma(BlurStrength.DEFAULT), 0.0005f)
    assertEquals(BlurStrength.MIN_SIGMA_FRACTION, sigma(0f), 1e-6f)
    assertEquals(BlurStrength.MAX_SIGMA_FRACTION, sigma(1f), 1e-6f)

    var previous = 0f
    for (i in 0..20) {
      val value = sigma(i / 20f)
      assertTrue("strength ${i / 20f} blurs more than the step before", value > previous)
      previous = value
    }
    // Log scale: every step of the slider multiplies the blur by the same factor.
    assertEquals(sigma(0.2f) / sigma(0.1f), sigma(0.9f) / sigma(0.8f), 1e-3f)
  }

  @Test
  fun `strength stays between 0 and 1`() {
    assertEquals(0f, BlurStrength.clamp(-2f), 0f)
    assertEquals(1f, BlurStrength.clamp(3f), 0f)
    assertEquals(BlurStrength.DEFAULT, BlurStrength.clamp(Float.NaN), 0f)
    assertEquals(BlurStrength.MAX_SIGMA_FRACTION, sigma(5f), 1e-6f)
  }

  @Test
  fun `presets and labels`() {
    assertTrue(BlurStrength.WEAK < BlurStrength.MEDIUM && BlurStrength.MEDIUM < BlurStrength.STRONG)
    assertEquals("Weak", BlurStrength.label(BlurStrength.WEAK))
    assertEquals("Medium", BlurStrength.label(BlurStrength.MEDIUM))
    assertEquals("Strong", BlurStrength.label(BlurStrength.STRONG))
    assertEquals(50, BlurStrength.percent(0.5f))
    assertEquals(100, BlurStrength.percent(2f))
  }

  @Test
  fun `the exported video uses more samples for stronger blurs`() {
    assertEquals(RegionBlurEffect.MIN_TAPS, RegionBlurEffect.tapsFor(1.5f))
    // Weak blur on a 1080p video: sigma 12.2 px, samples at most 3 px apart.
    assertEquals(13, RegionBlurEffect.tapsFor(1080 * sigma(BlurStrength.WEAK)))
    assertEquals(RegionBlurEffect.MAX_TAPS, RegionBlurEffect.tapsFor(1080 * sigma(BlurStrength.STRONG)))
    var previous = 0
    for (sigmaPx in 1..120) {
      val taps = RegionBlurEffect.tapsFor(sigmaPx.toFloat())
      assertTrue(taps >= previous)
      previous = taps
    }
  }

  @Test
  fun `stronger blur previews from a smaller copy of the frame`() {
    val weak = PreviewBlur.snapshotSize(1080, 608, sigma(0f))
    val medium = PreviewBlur.snapshotSize(1080, 608, sigma(BlurStrength.MEDIUM))
    val strong = PreviewBlur.snapshotSize(1080, 608, sigma(1f))
    assertTrue("$weak > $medium > $strong", weak.height > medium.height && medium.height > strong.height)
    assertEquals(1080f / 608f, medium.width.toFloat() / medium.height, 0.05f)

    // In the copy, the blur always spans about TARGET_SIGMA_PX pixels: the same blur as the
    // exported video, relative to the picture.
    for (strength in listOf(0f, 0.3f, 0.5f, 0.8f, 1f)) {
      val size = PreviewBlur.snapshotSize(1080, 608, sigma(strength))
      assertEquals("strength $strength", PreviewBlur.TARGET_SIGMA_PX, sigma(strength) * min(size.width, size.height), 0.15f)
    }

    // Portrait video: the shorter side is the width.
    assertEquals(60, PreviewBlur.snapshotSize(295, 525, sigma(BlurStrength.MEDIUM)).width)
  }

  @Test
  fun `the frame copy is never bigger than the view, nor empty`() {
    assertEquals(PreviewBlur.Size(40, 30), PreviewBlur.snapshotSize(40, 30, sigma(0f)))
    assertEquals(PreviewBlur.Size(1, 1), PreviewBlur.snapshotSize(0, 0, 0.02f))
  }

  @Test
  fun `box blur gives the same result as a straightforward box blur`() {
    val random = Random(42)
    val w = 23
    val h = 17
    val image = IntArray(w * h) { (0xFF shl 24) or random.nextInt(0x1000000) }
    for (radius in 1..3) {
      val fast = image.copyOf()
      PreviewBlur.boxBlur(fast, IntArray(w * h), w, h, radius)
      assertArrayEquals("radius $radius", naiveBoxBlur(image, w, h, radius), fast)
    }
  }

  @Test
  fun `box blur keeps flat areas and spreads a bright dot evenly`() {
    val gray = (0xFF shl 24) or 0x808080
    val flat = IntArray(9 * 7) { gray }
    PreviewBlur.boxBlur(flat, IntArray(flat.size), 9, 7)
    assertTrue(flat.all { it == gray })

    val black = 0xFF shl 24
    val dot = IntArray(11 * 11) { black }
    dot[5 * 11 + 5] = (0xFF shl 24) or 0xFAFAFA
    PreviewBlur.boxBlur(dot, IntArray(dot.size), 11, 11, radius = 2)
    for (y in 0 until 11) {
      for (x in 0 until 11) {
        val inside = x in 3..7 && y in 3..7
        val red = (dot[y * 11 + x] shr 16) and 0xFF
        // 250 spread over a 5 x 5 box: 10 in each pixel of the box, 0 elsewhere.
        assertEquals("($x, $y)", if (inside) 10 else 0, red)
      }
    }
  }

  private fun naiveBoxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
    fun pass(input: IntArray, horizontal: Boolean): IntArray {
      val out = IntArray(input.size)
      val count = 2 * radius + 1
      for (y in 0 until h) {
        for (x in 0 until w) {
          var r = 0
          var g = 0
          var b = 0
          for (k in -radius..radius) {
            val sx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
            val sy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
            val c = input[sy * w + sx]
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
          }
          out[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
        }
      }
      return out
    }
    return pass(pass(src, horizontal = true), horizontal = false)
  }
}
