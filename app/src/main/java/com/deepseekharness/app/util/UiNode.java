package com.deepseekharness.app.util;

import java.util.Locale;

/**
 * Android-free value object for one reportable accessibility node, used by the observe/act loop.
 *
 * <p>Flags are a bitset so the dump renderer and the diff can share one definition of "what is
 * interesting about this node". Refs are assigned by the caller in traversal order, so the same ref
 * denotes the same node across the full and outline render modes of a single dump.
 */
public final class UiNode {
  public static final int CLICK = 1,
      LONG_CLICK = 1 << 1,
      EDIT = 1 << 2,
      SCROLL = 1 << 3,
      CHECKABLE = 1 << 4,
      CHECKED = 1 << 5,
      DISABLED = 1 << 6,
      FOCUSED = 1 << 7,
      SELECTED = 1 << 8,
      PASSWORD = 1 << 9;

  /** A node the agent can act on (or the current input focus, which it may type into). */
  public static final int ACTIONABLE = CLICK | LONG_CLICK | EDIT | SCROLL | CHECKABLE | FOCUSED;

  public final int ref, flags, left, top, right, bottom;
  public final String cls, id, label;

  public UiNode(
      int ref,
      String cls,
      String id,
      String label,
      int flags,
      int left,
      int top,
      int right,
      int bottom) {
    this.ref = ref;
    this.cls = cls == null ? "" : cls;
    this.id = id == null ? "" : id;
    this.label = label == null ? "" : label;
    this.flags = flags;
    this.left = left;
    this.top = top;
    this.right = right;
    this.bottom = bottom;
  }

  public int centerX() {
    return left + (right - left) / 2;
  }

  public int centerY() {
    return top + (bottom - top) / 2;
  }

  public boolean has(int flag) {
    return (flags & flag) != 0;
  }

  public boolean actionable() {
    return (flags & ACTIONABLE) != 0;
  }

  /** Identity that survives scrolling and re-layout: class + resource id + visible label. */
  public String identity() {
    return cls + '\u0001' + id + '\u0001' + label;
  }

  /** True when the visible label contains {@code expect} (case-insensitive); empty expect matches. */
  public boolean labelContains(String expect) {
    if (expect == null || expect.trim().isEmpty()) return true;
    return label.toLowerCase(Locale.ROOT).contains(expect.trim().toLowerCase(Locale.ROOT));
  }

  /** One compact text line for the dump. Terse on purpose: every byte is a model token. */
  public String render() {
    StringBuilder sb = new StringBuilder();
    sb.append('#').append(ref).append(' ').append(cls.isEmpty() ? "View" : cls);
    if (!label.isEmpty()) sb.append(" \"").append(label).append('"');
    if (!id.isEmpty()) sb.append(" id=").append(id);
    String tags = tags();
    if (!tags.isEmpty()) sb.append(" [").append(tags).append(']');
    sb.append(" @").append(centerX()).append(',').append(centerY());
    sb.append(" (")
        .append(left)
        .append(',')
        .append(top)
        .append(',')
        .append(right)
        .append(',')
        .append(bottom)
        .append(')');
    return sb.toString();
  }

  String tags() {
    StringBuilder t = new StringBuilder();
    append(t, CLICK, "click");
    append(t, LONG_CLICK, "long");
    append(t, EDIT, "edit");
    append(t, SCROLL, "scroll");
    if (has(CHECKABLE)) comma(t).append(has(CHECKED) ? "on" : "off");
    append(t, SELECTED, "selected");
    append(t, FOCUSED, "focused");
    append(t, DISABLED, "disabled");
    append(t, PASSWORD, "password");
    return t.toString();
  }

  private void append(StringBuilder t, int flag, String name) {
    if (has(flag)) comma(t).append(name);
  }

  private StringBuilder comma(StringBuilder t) {
    if (t.length() > 0) t.append(',');
    return t;
  }
}
