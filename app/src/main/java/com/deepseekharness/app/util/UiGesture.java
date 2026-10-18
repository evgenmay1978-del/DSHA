package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An Android-free description of a multi-point gesture: long press, double tap, drag or pinch.
 *
 * <p>Coordinates are screen pixels in the same space as {@code /app/ui/dump}. The accessibility
 * service turns each {@link Stroke} into an Android {@code Path}; keeping the geometry here means
 * the rejection rules (empty gesture, absurd duration, too many pointers) live in one testable
 * place instead of being re-derived at the bridge.
 */
public final class UiGesture {

  /** The platform rejects a stroke shorter than this on some builds. */
  public static final int MIN_DURATION_MS = 1;

  /** Longer than this is a stuck finger, not a gesture; the caller should split the action. */
  public static final int MAX_DURATION_MS = 5000;

  /** Two pointers is all a pinch needs and the platform caps simultaneous strokes anyway. */
  public static final int MAX_STROKES = 2;

  /** How many points an interpolated stroke carries; enough for smooth drag and pinch. */
  private static final int STEPS = 8;

  /** One pointer travelling from A to B, or standing still for a long press. */
  public static final class Stroke {
    private final int[] xs;
    private final int[] ys;
    private final int startMs;
    private final int durationMs;

    Stroke(int[] xs, int[] ys, int startMs, int durationMs) {
      this.xs = xs;
      this.ys = ys;
      this.startMs = startMs;
      this.durationMs = durationMs;
    }

    public int pointCount() {
      return xs.length;
    }

    public int x(int index) {
      return xs[index];
    }

    public int y(int index) {
      return ys[index];
    }

    public int startMs() {
      return startMs;
    }

    public int durationMs() {
      return durationMs;
    }
  }

  /** A complete gesture: the strokes plus the time the platform needs to finish all of them. */
  public static final class Spec {
    private final List<Stroke> strokes;
    private final String kind;

    Spec(List<Stroke> strokes, String kind) {
      this.strokes = Collections.unmodifiableList(strokes);
      this.kind = kind;
    }

    public List<Stroke> strokes() {
      return strokes;
    }

    /** Short English token used in replies, for example {@code pinch}. */
    public String kind() {
      return kind;
    }

    /** Wall time after which the platform must have finished, plus a small slack. */
    public int totalMs() {
      int end = 0;
      for (Stroke s : strokes) end = Math.max(end, s.startMs + s.durationMs);
      return end;
    }
  }

  private UiGesture() {}

  /** Finger stays on the same point for {@code durationMs}. */
  public static Spec longPress(int x, int y, int durationMs) {
    checkPoint(x, y);
    int dur = clampDuration(durationMs, 800, 400);
    int[] xs = {x, x};
    int[] ys = {y, y};
    List<Stroke> strokes = new ArrayList<>();
    strokes.add(new Stroke(xs, ys, 0, dur));
    return new Spec(strokes, "long press");
  }

  /** Two short taps on the same point, kept close enough for the platform to pair them. */
  public static Spec doubleTap(int x, int y) {
    checkPoint(x, y);
    int[] xs = {x, x};
    int[] ys = {y, y};
    List<Stroke> strokes = new ArrayList<>();
    strokes.add(new Stroke(xs, ys, 0, 40));
    strokes.add(new Stroke(xs.clone(), ys.clone(), 90, 40));
    return new Spec(strokes, "double tap");
  }

  /** One finger from start to end, interpolated so the move is a drag and not a fling. */
  public static Spec drag(int x1, int y1, int x2, int y2, int durationMs) {
    checkPoint(x1, y1);
    checkPoint(x2, y2);
    int dur = clampDuration(durationMs, 500, 100);
    List<Stroke> strokes = new ArrayList<>();
    strokes.add(line(x1, y1, x2, y2, dur));
    return new Spec(strokes, "drag");
  }

  /**
   * Two fingers on a horizontal axis around {@code (cx, cy)}, moving apart ({@code endRadius >
   * startRadius}, zoom in) or together (zoom out).
   */
  public static Spec pinch(int cx, int cy, int startRadius, int endRadius, int durationMs) {
    checkPoint(cx, cy);
    int from = clampRadius(startRadius);
    int to = clampRadius(endRadius);
    int dur = clampDuration(durationMs, 400, 100);
    List<Stroke> strokes = new ArrayList<>();
    strokes.add(line(cx - from, cy, cx - to, cy, dur));
    strokes.add(line(cx + from, cy, cx + to, cy, dur));
    return new Spec(strokes, from > to ? "pinch in" : "pinch out");
  }

  private static Stroke line(int x1, int y1, int x2, int y2, int durationMs) {
    int[] xs = new int[STEPS + 1];
    int[] ys = new int[STEPS + 1];
    for (int i = 0; i <= STEPS; i++) {
      xs[i] = x1 + Math.round((x2 - x1) * (float) i / STEPS);
      ys[i] = y1 + Math.round((y2 - y1) * (float) i / STEPS);
    }
    return new Stroke(xs, ys, 0, durationMs);
  }

  private static void checkPoint(int x, int y) {
    if (x < 0 || y < 0) throw new IllegalArgumentException("coordinates must be non-negative");
  }

  private static int clampRadius(int radius) {
    if (radius < 1) throw new IllegalArgumentException("radius must be positive");
    return Math.min(radius, 2000);
  }

  private static int clampDuration(int durationMs, int fallback, int minimum) {
    int value = durationMs <= 0 ? fallback : durationMs;
    return Math.max(minimum, Math.min(value, MAX_DURATION_MS));
  }
}
