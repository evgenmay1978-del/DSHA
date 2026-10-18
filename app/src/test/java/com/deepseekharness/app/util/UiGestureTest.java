package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class UiGestureTest {

  @Test
  public void longPressKeepsOnePointerAndClampsShortDurations() {
    UiGesture.Spec spec = UiGesture.longPress(100, 200, 50);
    assertEquals("long press", spec.kind());
    assertEquals(1, spec.strokes().size());
    UiGesture.Stroke stroke = spec.strokes().get(0);
    assertEquals(2, stroke.pointCount());
    assertEquals(100, stroke.x(0));
    assertEquals(200, stroke.y(1));
    // 50 ms would be registered as a tap, so a long press never goes below 400 ms.
    assertEquals(400, stroke.durationMs());
    assertEquals(400, spec.totalMs());
  }

  @Test
  public void longPressKeepsRequestedDurationAndReportsTotalTime() {
    UiGesture.Spec spec = UiGesture.longPress(10, 20, 1500);
    assertEquals(1500, spec.strokes().get(0).durationMs());
    assertEquals(1500, spec.totalMs());
  }

  @Test
  public void doubleTapUsesTwoNonOverlappingStrokes() {
    UiGesture.Spec spec = UiGesture.doubleTap(540, 1200);
    assertEquals(2, spec.strokes().size());
    UiGesture.Stroke first = spec.strokes().get(0);
    UiGesture.Stroke second = spec.strokes().get(1);
    assertTrue(first.startMs() + first.durationMs() <= second.startMs());
    assertEquals(540, second.x(0));
    assertEquals(1200, second.y(0));
  }

  @Test
  public void dragInterpolatesBothEndpoints() {
    UiGesture.Spec spec = UiGesture.drag(0, 100, 800, 900, 300);
    UiGesture.Stroke stroke = spec.strokes().get(0);
    assertTrue(stroke.pointCount() > 2);
    assertEquals(0, stroke.x(0));
    assertEquals(100, stroke.y(0));
    assertEquals(800, stroke.x(stroke.pointCount() - 1));
    assertEquals(900, stroke.y(stroke.pointCount() - 1));
    assertEquals(300, stroke.durationMs());
  }

  @Test
  public void pinchOutMovesFingersApartAndPinchInBringsThemTogether() {
    UiGesture.Spec out = UiGesture.pinch(500, 1000, 100, 400, 400);
    assertEquals("pinch out", out.kind());
    assertEquals(2, out.strokes().size());
    UiGesture.Stroke left = out.strokes().get(0);
    UiGesture.Stroke right = out.strokes().get(1);
    // Pinch out: the left finger travels further left, the right finger further right.
    assertTrue(left.x(0) > left.x(left.pointCount() - 1));
    assertTrue(right.x(0) < right.x(right.pointCount() - 1));
    assertEquals(500 - 100, left.x(0));
    assertEquals(500 + 400, right.x(right.pointCount() - 1));

    UiGesture.Spec in = UiGesture.pinch(500, 1000, 400, 100, 400);
    assertEquals("pinch in", in.kind());
    assertEquals(500 - 400, in.strokes().get(0).x(0));
  }

  @Test
  public void absurdDurationsAreClampedNotRejected() {
    UiGesture.Spec spec = UiGesture.drag(0, 0, 100, 100, 999999);
    assertEquals(UiGesture.MAX_DURATION_MS, spec.strokes().get(0).durationMs());
  }

  @Test
  public void negativeCoordinatesAndZeroRadiusAreRejected() {
    try {
      UiGesture.longPress(-1, 10, 800);
      fail("negative x accepted");
    } catch (IllegalArgumentException expected) {
      assertEquals("coordinates must be non-negative", expected.getMessage());
    }
    try {
      UiGesture.pinch(100, 100, 0, 200, 400);
      fail("zero radius accepted");
    } catch (IllegalArgumentException expected) {
      assertEquals("radius must be positive", expected.getMessage());
    }
  }
}
