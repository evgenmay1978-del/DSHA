package com.deepseekharness.app.util;

import java.util.Locale;

/**
 * One screen action for {@code /app/act}: what to do, where, and how long to wait for the screen to
 * settle.
 *
 * <p>Android-free on purpose. The action vocabulary, the stale-ref guard, fraction-to-pixel
 * conversion and the result line are pure logic with host tests; the accessibility service only
 * performs the resolved action and polls snapshots until the screen stops moving.
 */
public final class UiAct {

  /** Actions the endpoint accepts; {@link #LOOK} performs nothing and only observes. */
  public enum Action {
    LOOK,
    TAP,
    TYPE,
    SCROLL,
    BACK,
    HOME,
    SWIPE;

    /** Case-insensitive parse with the aliases agents reach for; {@code null} when unknown. */
    public static Action parse(String value) {
      if (value == null) return null;
      String v = value.trim().toLowerCase(Locale.ROOT);
      switch (v) {
        case "look":
        case "see":
        case "observe":
          return LOOK;
        case "tap":
        case "click":
          return TAP;
        case "type":
        case "input":
          return TYPE;
        case "scroll":
          return SCROLL;
        case "back":
          return BACK;
        case "home":
          return HOME;
        case "swipe":
          return SWIPE;
        default:
          return null;
      }
    }

    /** Stable lower-case name used in the result line. */
    public String token() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Default wait for the UI to settle after an action. */
  public static final int DEFAULT_SETTLE_MS = 1500;

  /** Longer than this is a stuck screen, not an animation; the caller should re-observe instead. */
  public static final int MAX_SETTLE_MS = 5000;

  /** Consecutive identical polls that mean the screen stopped moving. */
  public static final int STABLE_POLLS = 2;

  /** Coordinate sentinel meaning "not supplied". */
  public static final int ABSENT = -1;

  public final Action action;

  /** Ref from an earlier dump; 0 when the action targets text or a point instead. */
  public final int ref;

  /** Substring the ref must still show; the guard against acting on a reused ref. */
  public final String expect;

  public final String text;

  /** True when {@code text} was supplied, so an empty value can mean "clear the field". */
  public final boolean textGiven;

  public final int x;
  public final int y;
  public final double fx;
  public final double fy;
  public final String direction;
  public final int x1;
  public final int y1;
  public final int x2;
  public final int y2;
  public final double fx1;
  public final double fy1;
  public final double fx2;
  public final double fy2;
  public final int durationMs;
  public final int settleMs;
  public final boolean changedOnly;
  public final boolean snapshot;
  public final String mode;

  private UiAct(Builder b) {
    this.action = b.action;
    this.ref = b.ref;
    this.expect = b.expect;
    this.text = b.text;
    this.textGiven = b.textGiven;
    this.x = b.x;
    this.y = b.y;
    this.fx = b.fx;
    this.fy = b.fy;
    this.direction = b.direction;
    this.x1 = b.x1;
    this.y1 = b.y1;
    this.x2 = b.x2;
    this.y2 = b.y2;
    this.fx1 = b.fx1;
    this.fy1 = b.fy1;
    this.fx2 = b.fx2;
    this.fy2 = b.fy2;
    this.durationMs = b.durationMs;
    this.settleMs = b.settleMs;
    this.changedOnly = b.changedOnly;
    this.snapshot = b.snapshot;
    this.mode = b.mode;
  }

  /** Settle window clamped to the contract: 0 (do not wait) through {@link #MAX_SETTLE_MS}. */
  public static int clampSettle(int requested) {
    return Math.max(0, Math.min(MAX_SETTLE_MS, requested));
  }

  /** Fraction of the screen to a pixel inside it; out-of-range input is clamped, absent is -1. */
  public static int pixel(double fraction, int size) {
    if (fraction < 0 || size <= 0) return ABSENT;
    long point = Math.round(fraction * size);
    return (int) Math.max(0, Math.min(size - 1L, point));
  }

  /**
   * Why a ref from an earlier dump cannot be used now, or null when it still points at the target.
   * The label check is what turns "ref reused by a rebuilt list" into a refusal instead of a tap on
   * whatever moved into that slot.
   */
  public static Resolved resolveRef(UiSnapshot seen, UiSnapshot now, int ref, String expect) {
    if (seen == null) return new Resolved(null, "REF_NO_DUMP");
    UiNode target = seen.byRef(ref);
    if (target == null) return new Resolved(null, "REF_NOT_FOUND");
    String identity = target.identity();
    // Lists hold many identical controls ("Delete" on every row): a ref is the nth such control,
    // not "the first one that looks the same". Keep the ordinal across the two snapshots, and
    // refuse to guess when the number of identical controls changed (a row was added or removed).
    int ordinal = 0;
    int total = 0;
    for (UiNode node : seen.nodes()) {
      if (!node.identity().equals(identity)) continue;
      if (node == target) ordinal = total;
      total++;
    }
    int nowTotal = 0;
    UiNode match = null;
    for (UiNode node : now.nodes()) {
      if (!node.identity().equals(identity)) continue;
      if (nowTotal == ordinal) match = node;
      nowTotal++;
    }
    if (nowTotal == 0) return new Resolved(null, "REF_STALE");
    if (nowTotal != total) return new Resolved(null, "REF_AMBIGUOUS");
    if (match == null) return new Resolved(null, "REF_STALE");
    if (!match.labelContains(expect)) return new Resolved(null, "REF_STALE");
    return new Resolved(match, null);
  }

  /** Result of {@link #resolveRef}: the node to act on, or the code naming why it is unsafe. */
  public static final class Resolved {
    public final UiNode node;
    public final String problem;

    public Resolved(UiNode node, String problem) {
      this.node = node;
      this.problem = problem;
    }
  }

  /**
   * Result line when the screen moved to another window while settling: the action did land, but the
   * reply cannot describe the new screen, so the caller is pointed back to {@code /app/ui/dump}.
   */
  public static String statusTransition(Action action, int ref, long waitedMs) {
    StringBuilder sb = new StringBuilder("ok action=").append(action.token());
    if (ref > 0) sb.append(" ref=").append(ref);
    sb.append(" changed=true targetChangedAfterExecution=true settled=false");
    sb.append(" waited=").append(Math.max(0, waitedMs)).append("ms");
    return sb.toString();
  }

  /** First result line: what was done, whether the screen changed and how long settling took. */
  public static String status(
      Action action, int ref, boolean changed, boolean settled, long waitedMs, UiSnapshot screen) {
    StringBuilder sb = new StringBuilder("ok action=").append(action.token());
    if (ref > 0) sb.append(" ref=").append(ref);
    sb.append(" changed=").append(changed);
    sb.append(" settled=").append(settled);
    sb.append(" waited=").append(Math.max(0, waitedMs)).append("ms");
    sb.append(" screen=").append(screen.screenId());
    sb.append(" nodes=").append(screen.nodes().size());
    return sb.toString();
  }

  /**
   * One observation of the screen: what it looked like and whether it stopped moving before the
   * settle window expired. {@code settled=false} tells the caller the reply is a snapshot of a
   * still-animating screen, not the final state.
   */
  public static final class Observation {
    public final UiSnapshot screen;
    public final boolean settled;

    /** True when the screen moved to another window while settling; the reply cannot describe it. */
    public final boolean targetChanged;

    public Observation(UiSnapshot screen, boolean settled) {
      this(screen, settled, false);
    }

    public Observation(UiSnapshot screen, boolean settled, boolean targetChanged) {
      this.screen = screen;
      this.settled = settled;
      this.targetChanged = targetChanged;
    }
  }

  /** Mutable request builder; {@link #build()} rejects actions that cannot be performed. */
  public static final class Builder {
    private Action action = Action.LOOK;
    private int ref;
    private String expect = "";
    private String text = "";
    private boolean textGiven;
    private int x = ABSENT;
    private int y = ABSENT;
    private double fx = -1;
    private double fy = -1;
    private String direction = "down";
    private int x1 = ABSENT;
    private int y1 = ABSENT;
    private int x2 = ABSENT;
    private int y2 = ABSENT;
    private double fx1 = -1;
    private double fy1 = -1;
    private double fx2 = -1;
    private double fy2 = -1;
    private int durationMs = 300;
    private int settleMs = DEFAULT_SETTLE_MS;
    private boolean changedOnly;
    private boolean snapshot = true;
    private String mode = "outline";

    public Builder action(Action value) {
      if (value != null) action = value;
      return this;
    }

    public Builder ref(int value) {
      ref = Math.max(0, value);
      return this;
    }

    public Builder expect(String value) {
      expect = value == null ? "" : value;
      return this;
    }

    public Builder text(String value) {
      text = value == null ? "" : value;
      textGiven = true;
      return this;
    }

    public Builder at(int px, int py) {
      x = px;
      y = py;
      return this;
    }

    public Builder atFraction(double fxv, double fyv) {
      fx = fxv;
      fy = fyv;
      return this;
    }

    public Builder swipe(int ax, int ay, int bx, int by, int ms) {
      x1 = ax;
      y1 = ay;
      x2 = bx;
      y2 = by;
      durationMs = ms;
      return this;
    }

    public Builder swipeFraction(double ax, double ay, double bx, double by, int ms) {
      fx1 = ax;
      fy1 = ay;
      fx2 = bx;
      fy2 = by;
      durationMs = ms;
      return this;
    }

    public Builder direction(String value) {
      direction = value == null ? "down" : value.trim().toLowerCase(Locale.ROOT);
      return this;
    }

    public Builder settleMs(int value) {
      settleMs = clampSettle(value);
      return this;
    }

    public Builder changedOnly(boolean value) {
      changedOnly = value;
      return this;
    }

    public Builder snapshot(boolean value) {
      snapshot = value;
      return this;
    }

    public Builder mode(String value) {
      mode = value == null ? "outline" : value;
      return this;
    }

    public UiAct build() {
      switch (action) {
        case TAP:
          if (ref <= 0 && !(textGiven && !text.isEmpty()) && !hasPoint())
            throw new IllegalArgumentException("TAP_TARGET_REQUIRED: ref, text or x/y");
          break;
        case TYPE:
          if (!textGiven) throw new IllegalArgumentException("TYPE_TEXT_REQUIRED");
          break;
        case SWIPE:
          if (!hasSwipe()) throw new IllegalArgumentException("SWIPE_COORDS_REQUIRED");
          break;
        case SCROLL:
          if (!isDirection(direction))
            throw new IllegalArgumentException("SCROLL_DIRECTION_INVALID: up|down|left|right");
          break;
        default:
          break;
      }
      return new UiAct(this);
    }

    private boolean hasPoint() {
      return (x != ABSENT && y != ABSENT) || (fx >= 0 && fy >= 0);
    }

    private boolean hasSwipe() {
      return (x1 != ABSENT && y1 != ABSENT && x2 != ABSENT && y2 != ABSENT)
          || (fx1 >= 0 && fy1 >= 0 && fx2 >= 0 && fy2 >= 0);
    }

    private static boolean isDirection(String value) {
      return value.equals("up")
          || value.equals("down")
          || value.equals("left")
          || value.equals("right");
    }
  }
}
