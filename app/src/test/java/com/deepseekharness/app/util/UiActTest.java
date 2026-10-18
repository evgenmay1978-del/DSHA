package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class UiActTest {

  @Test
  public void actionParsesAliasesAndRejectsUnknown() {
    assertEquals(UiAct.Action.TAP, UiAct.Action.parse(" click "));
    assertEquals(UiAct.Action.TYPE, UiAct.Action.parse("INPUT"));
    assertEquals(UiAct.Action.LOOK, UiAct.Action.parse("observe"));
    assertNull(UiAct.Action.parse("frobnicate"));
    assertNull(UiAct.Action.parse(null));
  }

  @Test
  public void settleIsClampedToTheContract() {
    assertEquals(0, UiAct.clampSettle(-50));
    assertEquals(1500, UiAct.clampSettle(UiAct.DEFAULT_SETTLE_MS));
    assertEquals(UiAct.MAX_SETTLE_MS, UiAct.clampSettle(9000));
  }

  @Test
  public void tapRequiresATarget() {
    try {
      new UiAct.Builder().action(UiAct.Action.TAP).build();
      fail("tap without a target must be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().startsWith("TAP_TARGET_REQUIRED"));
    }
  }

  @Test
  public void tapAcceptsRefTextOrPoint() {
    assertEquals(7, new UiAct.Builder().action(UiAct.Action.TAP).ref(7).build().ref);
    assertEquals(
        "Отправить", new UiAct.Builder().action(UiAct.Action.TAP).text("Отправить").build().text);
    assertEquals(
        0.5, new UiAct.Builder().action(UiAct.Action.TAP).atFraction(0.5, 0.8).build().fx, 0.0001);
  }

  @Test
  public void typeAcceptsAnExplicitlyEmptyTextAsClear() {
    try {
      new UiAct.Builder().action(UiAct.Action.TYPE).build();
      fail("type without text must be rejected: absent and empty differ");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().startsWith("TYPE_TEXT_REQUIRED"));
    }
    UiAct clear = new UiAct.Builder().action(UiAct.Action.TYPE).text("").build();
    assertTrue(clear.textGiven);
    assertEquals("", clear.text);
  }

  @Test
  public void swipeNeedsBothEndsInPixelsOrFractions() {
    try {
      new UiAct.Builder().action(UiAct.Action.SWIPE).build();
      fail("swipe without coordinates must be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().startsWith("SWIPE_COORDS_REQUIRED"));
    }
    UiAct drag = new UiAct.Builder().action(UiAct.Action.SWIPE).swipe(1, 2, 3, 4, 300).build();
    assertEquals(3, drag.x2);
    assertEquals(4, drag.y2);
  }

  @Test
  public void scrollRejectsAnUnknownDirection() {
    try {
      new UiAct.Builder().action(UiAct.Action.SCROLL).direction("sideways").build();
      fail("unknown scroll direction must be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().startsWith("SCROLL_DIRECTION_INVALID"));
    }
    assertEquals(
        "up", new UiAct.Builder().action(UiAct.Action.SCROLL).direction("UP").build().direction);
  }

  @Test
  public void refGuardDistinguishesMissingFromStale() {
    UiNode node = new UiNode(3, "Button", "", "Отправить", UiNode.CLICK, 0, 0, 10, 10);
    assertNull(UiAct.refProblem(node, "отправ"));
    assertNull(UiAct.refProblem(node, ""));
    assertEquals("REF_STALE", UiAct.refProblem(node, "Черновик"));
    assertEquals("REF_NOT_FOUND", UiAct.refProblem(null, "Отправить"));
  }

  @Test
  public void fractionsMapInsideTheScreenAndClamp() {
    assertEquals(500, UiAct.pixel(0.5, 1000));
    assertEquals(0, UiAct.pixel(0.0, 1000));
    assertEquals(999, UiAct.pixel(2.0, 1000));
    assertEquals(UiAct.ABSENT, UiAct.pixel(-0.1, 1000));
    assertEquals(UiAct.ABSENT, UiAct.pixel(0.5, 0));
  }

  @Test
  public void statusLineCarriesWhatTheAgentNeeds() {
    UiSnapshot.Builder builder =
        UiSnapshot.builder().window("com.example", "").display(0, 1080, 2400, 440, 0);
    builder.add("Button", "", "Отправить", UiNode.CLICK, 0, 0, 10, 10);
    UiSnapshot snap = builder.build();
    String line = UiAct.status(UiAct.Action.TAP, 3, true, 420, snap);
    assertTrue(line.startsWith("ok action=tap ref=3 changed=true waited=420ms"));
    assertTrue(line.contains("screen=" + snap.screenId()));
    assertTrue(line.contains("nodes=1"));
    assertFalse(UiAct.status(UiAct.Action.LOOK, 0, false, 0, snap).contains("ref="));
  }

  @Test
  public void sameScreenIgnoresNothingButLabelAndState() {
    UiSnapshot one = snapshot("Отправить", 0, 0, 10, 10);
    UiSnapshot same = snapshot("Отправить", 5, 5, 20, 20);
    UiSnapshot changed = snapshot("Черновик", 0, 0, 10, 10);
    assertTrue(one.sameScreen(same));
    assertFalse(one.sameScreen(changed));
    assertFalse(one.sameScreen(null));
  }

  private static UiSnapshot snapshot(String label, int left, int top, int right, int bottom) {
    UiSnapshot.Builder builder =
        UiSnapshot.builder().window("com.example", "").display(0, 1080, 2400, 440, 0);
    builder.add("Button", "", label, UiNode.CLICK, left, top, right, bottom);
    return builder.build();
  }
}
