package com.deepseekharness.app.util;

/**
 * 局域网页面的设置持久化。DSH 0.2.0 及更早：非回环页面只把设置留在内存，DSHA 改为强制 host。
 *
 * <p>DSH 0.2.1 起上游删除了 persistence 分支，设置一律由 Host 保存，此时无需补丁；但只在模块
 * 确实是新结构时才放行，其它未知结构仍拒绝，避免静默丢失局域网设置保存。
 */
public final class LanSettingsPatch {
  public static final String BEFORE =
      "const persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";";
  public static final String AFTER =
      "const persistence = \"host\"; // DSHA patch: LAN 代理场景强制 host 持久化";

  /** 0.2.1 apply()：describe mirror 不再接收 persistence，设置表单只走 Host。 */
  static final String HOST_ONLY_MIRROR = "const mirror = new SettingsDescribeMirror(ctx);";

  private LanSettingsPatch() {}

  public static String apply(String source) {
    if (source != null && !source.contains(BEFORE) && !source.contains(AFTER) && hostOnly(source))
      return source;
    return ExactTextPatch.apply(source, BEFORE, AFTER);
  }

  private static boolean hostOnly(String source) {
    int at = source.indexOf(HOST_ONLY_MIRROR);
    return at >= 0
        && source.indexOf(HOST_ONLY_MIRROR, at + HOST_ONLY_MIRROR.length()) < 0
        && !source.contains("persistence === \"memory\"")
        && !source.contains("isLoopback ? \"host\"");
  }
}
