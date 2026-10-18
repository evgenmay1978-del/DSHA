package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class UiSnapshotTest {

  private UiSnapshot.Builder screen() {
    return UiSnapshot.builder()
        .window("com.example.app", "com.example.app.MainActivity")
        .display(0, 1080, 2400, 420, 0);
  }

  @Test
  public void refsAreTraversalOrderAndStableAcrossModes() {
    UiSnapshot.Builder b = screen();
    b.add("TextView", "", "Заголовок", 0, 0, 100, 1080, 180);
    b.add("Button", "id/ok", "ОК", UiNode.CLICK, 0, 200, 540, 320);
    UiSnapshot snap = b.build();

    assertEquals(1, snap.nodes().get(0).ref);
    assertEquals(2, snap.nodes().get(1).ref);
    // The clickable button keeps ref #2 even when the plain label is dropped in outline mode.
    assertTrue(snap.render(UiSnapshot.Mode.OUTLINE).contains("#2 Button"));
    assertFalse(snap.render(UiSnapshot.Mode.OUTLINE).contains("#1 TextView"));
    assertTrue(snap.render(UiSnapshot.Mode.FULL).contains("#1 TextView"));
  }

  @Test
  public void renderKeepsCyrillicAndReportsCenterAndBounds() {
    UiSnapshot snap = screen().build();
    snap.nodes(); // no-op guard
    UiSnapshot.Builder b = screen();
    UiNode n =
        b.add("EditText", "id/field", "Пароль", UiNode.EDIT | UiNode.FOCUSED, 40, 400, 1040, 520);
    String line = n.render();
    assertTrue(line.contains("\"Пароль\""));
    assertTrue(line.contains("edit"));
    assertTrue(line.contains("focused"));
    assertTrue(line.contains("@540,460"));
    assertTrue(line.contains("(40,400,1040,520)"));
  }

  @Test
  public void passwordNodeIsFlaggedNotTranscribed() {
    UiNode n = new UiNode(1, "EditText", "id/pw", "", UiNode.EDIT | UiNode.PASSWORD, 0, 0, 100, 50);
    assertTrue(n.render().contains("password"));
    assertFalse(n.render().contains("\""));
  }

  @Test
  public void identicalScreenHasEqualFingerprint() {
    UiSnapshot a = screen().build();
    UiSnapshot.Builder bb = screen();
    UiSnapshot b = bb.build();
    assertEquals(a.screenId(), b.screenId());
  }

  @Test
  public void pixelJitterDoesNotChangeFingerprintButNewControlDoes() {
    UiSnapshot.Builder a = screen();
    a.add("Button", "id/ok", "ОК", UiNode.CLICK, 0, 200, 540, 320);
    UiSnapshot.Builder jittered = screen();
    jittered.add("Button", "id/ok", "ОК", UiNode.CLICK, 1, 201, 541, 321);
    assertEquals(a.build().screenId(), jittered.build().screenId());

    UiSnapshot.Builder grown = screen();
    grown.add("Button", "id/ok", "ОК", UiNode.CLICK, 0, 200, 540, 320);
    grown.add("Button", "id/cancel", "Отмена", UiNode.CLICK, 540, 200, 1080, 320);
    assertNotEquals(a.build().screenId(), grown.build().screenId());
  }

  @Test
  public void diffReportsSameScreenInOneLine() {
    UiSnapshot.Builder a = screen();
    a.add("Button", "id/ok", "ОК", UiNode.CLICK, 0, 200, 540, 320);
    UiSnapshot.Builder b = screen();
    b.add("Button", "id/ok", "ОК", UiNode.CLICK, 0, 200, 540, 320);
    String out = b.build().diff(a.build());
    assertTrue(out.contains("unchanged"));
    assertFalse(out.contains("\nid/ok")); // no per-node body when nothing moved
  }

  @Test
  public void diffReportsAddedRemovedAndStateChange() {
    UiSnapshot.Builder before = screen();
    before.add("CheckBox", "id/agree", "Согласен", UiNode.CHECKABLE, 0, 100, 1080, 200);
    before.add("TextView", "id/err", "Ошибка", 0, 0, 300, 1080, 360);

    UiSnapshot.Builder after = screen();
    after.add(
        "CheckBox", "id/agree", "Согласен", UiNode.CHECKABLE | UiNode.CHECKED, 0, 100, 1080, 200);
    after.add("Button", "id/next", "Далее", UiNode.CLICK, 0, 400, 1080, 520);

    String out = after.build().diff(before.build());
    assertTrue(out.contains("+1 -1 ~1"));
    assertTrue(out.contains("+ #2 Button \"Далее\""));
    assertTrue(out.contains("~ #1 CheckBox"));
    assertTrue(out.contains("- #2 TextView \"Ошибка\""));
  }

  @Test
  public void diffAgainstNullIsAFullDump() {
    UiSnapshot.Builder b = screen();
    b.add("Button", "id/ok", "ОК", UiNode.CLICK, 0, 200, 540, 320);
    String out = b.build().diff(null);
    assertTrue(out.contains("#1 Button"));
    assertFalse(out.contains("changed vs previous"));
  }

  @Test
  public void expectGuardMatchesVisibleLabelCaseInsensitively() {
    UiNode n = new UiNode(1, "Button", "id/ok", "Войти", UiNode.CLICK, 0, 0, 100, 50);
    assertTrue(n.labelContains("войти"));
    assertTrue(n.labelContains(""));
    assertTrue(n.labelContains(null));
    assertFalse(n.labelContains("отмена"));
  }

  @Test
  public void focusMoveBetweenIdenticalFieldsIsReportedNotUnchanged() {
    UiSnapshot.Builder before = screen();
    before.add("EditText", "", "", UiNode.EDIT | UiNode.FOCUSED, 0, 100, 1080, 200);
    before.add("EditText", "", "", UiNode.EDIT, 0, 300, 1080, 400);
    UiSnapshot.Builder after = screen();
    after.add("EditText", "", "", UiNode.EDIT, 0, 100, 1080, 200);
    after.add("EditText", "", "", UiNode.EDIT | UiNode.FOCUSED, 0, 300, 1080, 400);

    UiSnapshot a = before.build(), b = after.build();
    assertEquals("focus is not structure", a.screenId(), b.screenId());
    String out = b.diff(a);
    assertFalse(out.contains("unchanged"));
    assertTrue(out.contains("[edit,focused]"));
  }

  @Test
  public void longLabelsAreCutWithEllipsis() {
    UiSnapshot.Builder b = screen();
    UiNode n = b.add("TextView", "", "я".repeat(5000), 0, 0, 0, 100, 50);
    assertEquals(UiSnapshot.LABEL_LIMIT + 1, n.label.length());
    assertTrue(n.label.endsWith("…"));
  }

  @Test
  public void renderAndDiffStayUnderOutputLimitAndSayWhatWasCut() {
    UiSnapshot.Builder b = screen();
    for (int i = 0; i < 200; i++)
      b.add("TextView", "id/row" + i, "x".repeat(150) + i, UiNode.CLICK, 0, i, 1080, i + 10);
    UiSnapshot big = b.build();

    String full = big.render(UiSnapshot.Mode.FULL);
    assertTrue(full.length() <= UiSnapshot.OUTPUT_LIMIT + 120);
    assertTrue(full.contains("more lines not shown"));

    String delta = big.diff(screen().build());
    assertTrue(delta.length() <= UiSnapshot.OUTPUT_LIMIT + 120);
    assertTrue(delta.contains("more lines not shown"));
  }
}
