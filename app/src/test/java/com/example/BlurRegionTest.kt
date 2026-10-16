package com.example

import com.example.media.BlurHandle
import com.example.media.BlurRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurRegionTest {

  private val eps = 1e-5f

  private fun assertRegion(expected: BlurRegion, actual: BlurRegion) {
    assertEquals("left", expected.left, actual.left, eps)
    assertEquals("top", expected.top, actual.top, eps)
    assertEquals("right", expected.right, actual.right, eps)
    assertEquals("bottom", expected.bottom, actual.bottom, eps)
  }

  @Test
  fun `moving keeps the size and stays inside the frame`() {
    val moved = BlurRegion(0.1f, 0.7f, 0.9f, 0.9f).moveBy(0.5f, 0.5f)
    assertRegion(BlurRegion(0.2f, 0.8f, 1f, 1f), moved)

    val backToCorner = moved.moveBy(-5f, -5f)
    assertRegion(BlurRegion(0f, 0f, 0.8f, 0.2f), backToCorner)
  }

  @Test
  fun `resizing moves only the dragged corner`() {
    val region = BlurRegion(0.2f, 0.2f, 0.6f, 0.6f)
    assertRegion(BlurRegion(0.2f, 0.2f, 0.7f, 0.8f), region.resize(BlurHandle.BOTTOM_RIGHT, 0.1f, 0.2f))
    assertRegion(BlurRegion(0.1f, 0.15f, 0.6f, 0.6f), region.resize(BlurHandle.TOP_LEFT, -0.1f, -0.05f))
    assertRegion(BlurRegion(0.2f, 0.1f, 0.9f, 0.6f), region.resize(BlurHandle.TOP_RIGHT, 0.3f, -0.1f))
    assertRegion(BlurRegion(0f, 0.2f, 0.6f, 1f), region.resize(BlurHandle.BOTTOM_LEFT, -1f, 1f))
  }

  @Test
  fun `resizing never makes the box smaller than the minimum`() {
    val region = BlurRegion(0.2f, 0.2f, 0.6f, 0.6f)
    val collapsed = region.resize(BlurHandle.TOP_LEFT, 1f, 1f)
    assertEquals(BlurRegion.MIN_SIZE, collapsed.width, eps)
    assertEquals(BlurRegion.MIN_SIZE, collapsed.height, eps)
    assertEquals(0.6f, collapsed.right, eps)
    assertEquals(0.6f, collapsed.bottom, eps)
  }

  @Test
  fun `a saved box decodes to the same box`() {
    val region = BlurRegion(0.12f, 0.7f, 0.88f, 0.9f)
    assertEquals(region, BlurRegion.decode(region.encode()))
  }

  @Test
  fun `malformed saved values are ignored`() {
    assertNull(BlurRegion.decode(null))
    assertNull(BlurRegion.decode(""))
    assertNull(BlurRegion.decode("0.1,0.2,0.3"))
    assertNull(BlurRegion.decode("a,b,c,d"))
    assertNull(BlurRegion.decode("NaN,0,1,1"))
  }

  @Test
  fun `out of range boxes are pulled back into the frame`() {
    val fixed = BlurRegion(-0.5f, 0.9f, 0.2f, 1.4f).sanitized()
    assertTrue(fixed.left >= 0f && fixed.top >= 0f && fixed.right <= 1f && fixed.bottom <= 1f)
    assertTrue(fixed.width >= BlurRegion.MIN_SIZE - eps && fixed.height >= BlurRegion.MIN_SIZE - eps)
  }

  @Test
  fun `valid boxes are left untouched`() {
    val region = BlurRegion(0.1f, 0.2f, 0.5f, 0.4f)
    assertSame(region, region.sanitized())
  }
}
