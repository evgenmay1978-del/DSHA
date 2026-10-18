package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An observed screen: window identity plus the reportable {@link UiNode}s, in traversal order.
 *
 * <p>The native accessibility walk fills a {@link Builder}; everything downstream (ref numbering,
 * the full/outline render, the structural fingerprint and the diff against a prior dump) is pure
 * logic with no Android types, so it is unit-testable on the host and shared by main screen and
 * virtual screen alike.
 */
public final class UiSnapshot {

  /** How much of the tree to render. OUTLINE keeps only actionable nodes to save tokens. */
  public enum Mode {
    FULL,
    OUTLINE;

    public static Mode parse(String value) {
      if (value == null) return FULL;
      String v = value.trim().toLowerCase(Locale.ROOT);
      return v.equals("outline") || v.equals("actions") ? OUTLINE : FULL;
    }
  }

  private final String pkg;
  private final String activity;
  private final int displayId;
  private final int width;
  private final int height;
  private final int dpi;
  private final int rotation;
  private final boolean truncated;
  private final List<UiNode> nodes;

  private UiSnapshot(Builder b) {
    this.pkg = b.pkg;
    this.activity = b.activity;
    this.displayId = b.displayId;
    this.width = b.width;
    this.height = b.height;
    this.dpi = b.dpi;
    this.rotation = b.rotation;
    this.truncated = b.truncated;
    this.nodes = b.nodes;
  }

  public String pkg() {
    return pkg;
  }

  public List<UiNode> nodes() {
    return nodes;
  }

  public UiNode byRef(int ref) {
    for (UiNode n : nodes) if (n.ref == ref) return n;
    return null;
  }

  /**
   * A stable fingerprint of the screen's structure: window identity plus each node's class, id,
   * label and action flags (not its exact pixel bounds, which jitter during animation). Equal ids
   * across two reads mean the agent can skip re-dumping. Hex so it is short in a result line.
   */
  public String screenId() {
    long h = 1125899906842597L;
    h = mix(h, pkg);
    h = mix(h, activity);
    h = h * 31 + rotation;
    for (UiNode n : nodes) {
      h = mix(h, n.cls);
      h = mix(h, n.id);
      h = mix(h, n.label);
      h = h * 31 + (n.flags & ~UiNode.FOCUSED); // focus alone is not a structural change
    }
    return Long.toHexString(h);
  }

  private static long mix(long h, String s) {
    if (s != null) for (int i = 0; i < s.length(); i++) h = h * 31 + s.charAt(i);
    return h * 1099511628211L;
  }

  private String header() {
    StringBuilder sb = new StringBuilder();
    sb.append("app=").append(pkg.isEmpty() ? "?" : pkg);
    if (!activity.isEmpty()) sb.append(" activity=").append(activity);
    sb.append(" display=").append(displayId);
    sb.append(" size=").append(width).append('x').append(height);
    if (dpi > 0) sb.append(" dpi=").append(dpi);
    sb.append(" rotation=").append(rotation);
    sb.append(" screen=").append(screenId());
    sb.append(" nodes=").append(nodes.size());
    if (truncated) sb.append(" (truncated: tree larger than budget)");
    return sb.toString();
  }

  /** Longest label kept per node; longer text is cut with an ellipsis. */
  public static final int LABEL_LIMIT = 160;

  /** Reply ceiling in characters, matching the previous dumper's ~12 KB stop. */
  public static final int OUTPUT_LIMIT = 12000;

  /** Full dump for a first read or an explicit full request. */
  public String render(Mode mode) {
    StringBuilder sb = new StringBuilder(header()).append('\n');
    List<String> lines = new ArrayList<>();
    for (UiNode n : nodes) {
      if (mode == Mode.OUTLINE && !n.actionable()) continue;
      lines.add(n.render());
    }
    if (lines.isEmpty()) sb.append("(no reportable nodes)\n");
    appendBounded(sb, lines);
    return sb.toString();
  }

  /**
   * Appends lines until the reply would pass {@link #OUTPUT_LIMIT}, then says how many were cut so
   * the agent knows the view is partial instead of silently missing controls.
   */
  private static void appendBounded(StringBuilder sb, List<String> lines) {
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (sb.length() + line.length() + 1 > OUTPUT_LIMIT) {
        sb.append("(output limit: ")
            .append(lines.size() - i)
            .append(" more lines not shown; use mode=outline or changed_only=1)\n");
        return;
      }
      sb.append(line).append('\n');
    }
  }

  /**
   * Diff against the previous snapshot: what the last action changed, instead of the whole tree.
   *
   * <p>Nodes are matched by {@link UiNode#identity()}. The agent gets {@code +} added, {@code -}
   * gone, {@code ~} state-changed (checked/selected/enabled/focus), and an unchanged count it does
   * not pay tokens to see. When the structure is identical, the reply is a single line so a
   * tap-that-did-nothing is cheap to observe.
   */
  public String diff(UiSnapshot prev) {
    if (prev == null) return render(Mode.FULL);
    // The fingerprint ignores focus on purpose (focus is not structure), so a focus move between
    // identical fields must still fall through to the per-node state comparison below.
    if (prev.screenId().equals(screenId()) && sameStates(prev)) {
      return header() + "\nunchanged (same screen as previous dump)\n";
    }
    List<UiNode> added = new ArrayList<>();
    List<UiNode> gone = new ArrayList<>();
    List<UiNode> changed = new ArrayList<>();
    int same = 0;

    boolean[] matched = new boolean[prev.nodes.size()];
    for (UiNode n : nodes) {
      int j = indexOfIdentity(prev.nodes, n.identity(), matched);
      if (j < 0) {
        added.add(n);
        continue;
      }
      matched[j] = true;
      UiNode before = prev.nodes.get(j);
      if (stateFlags(before) != stateFlags(n)) changed.add(n);
      else same++;
    }
    for (int j = 0; j < prev.nodes.size(); j++) if (!matched[j]) gone.add(prev.nodes.get(j));

    StringBuilder sb = new StringBuilder(header()).append('\n');
    sb.append("changed vs previous dump: +")
        .append(added.size())
        .append(" -")
        .append(gone.size())
        .append(" ~")
        .append(changed.size())
        .append(" =")
        .append(same)
        .append('\n');
    List<String> lines = new ArrayList<>();
    for (UiNode n : added) lines.add("+ " + n.render());
    for (UiNode n : changed) lines.add("~ " + n.render());
    for (UiNode n : gone) lines.add("- " + n.render());
    appendBounded(sb, lines);
    return sb.toString();
  }

  /** Position-wise state equality; only meaningful when the fingerprints already match. */
  private boolean sameStates(UiSnapshot prev) {
    if (prev.nodes.size() != nodes.size()) return false;
    for (int i = 0; i < nodes.size(); i++) {
      if (stateFlags(prev.nodes.get(i)) != stateFlags(nodes.get(i))) return false;
    }
    return true;
  }

  private static int stateFlags(UiNode n) {
    return n.flags & (UiNode.CHECKED | UiNode.DISABLED | UiNode.FOCUSED | UiNode.SELECTED);
  }

  private static int indexOfIdentity(List<UiNode> list, String identity, boolean[] used) {
    for (int i = 0; i < list.size(); i++) {
      if (!used[i] && list.get(i).identity().equals(identity)) return i;
    }
    return -1;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Fills a snapshot as the native tree walk visits reportable nodes. */
  public static final class Builder {
    private String pkg = "";
    private String activity = "";
    private int displayId;
    private int width;
    private int height;
    private int dpi;
    private int rotation;
    private boolean truncated;
    private final List<UiNode> nodes = new ArrayList<>();

    public Builder window(String pkg, String activity) {
      this.pkg = pkg == null ? "" : pkg;
      this.activity = activity == null ? "" : activity;
      return this;
    }

    public Builder display(int displayId, int width, int height, int dpi, int rotation) {
      this.displayId = displayId;
      this.width = width;
      this.height = height;
      this.dpi = dpi;
      this.rotation = rotation;
      return this;
    }

    public Builder truncated(boolean truncated) {
      this.truncated = truncated;
      return this;
    }

    /** Append the next reportable node; its ref is its 1-based position in traversal order. */
    public UiNode add(
        String cls, String id, String label, int flags, int left, int top, int right, int bottom) {
      if (label != null && label.length() > LABEL_LIMIT)
        label = label.substring(0, LABEL_LIMIT) + "…";
      UiNode node = new UiNode(nodes.size() + 1, cls, id, label, flags, left, top, right, bottom);
      nodes.add(node);
      return node;
    }

    public int size() {
      return nodes.size();
    }

    public UiSnapshot build() {
      return new UiSnapshot(this);
    }
  }
}
