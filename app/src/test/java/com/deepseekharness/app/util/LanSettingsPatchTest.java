package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class LanSettingsPatchTest {
  // dsh-client-ui-settings 0.2.0-rc.2 lib/client.js apply()
  private static final String V020 =
      "function apply(ctx) {\n"
          + "\tconst schema = new SettingsSchemaService(ctx);\n"
          + "\tconst persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";\n"
          + "\tconst mirror = new SettingsDescribeMirror(ctx, persistence);\n"
          + "}\n"
          + "if (this.persistence === \"memory\" || this.disposed) return"
          + " Promise.resolve(false);\n";
  // dsh-client-ui-settings 0.2.1-rc.2 lib/client.js apply(): the persistence branch is gone.
  private static final String V021 =
      "function apply(ctx) {\n"
          + "\tconst schema = new SettingsSchemaService(ctx);\n"
          + "\tconst mirror = new SettingsDescribeMirror(ctx);\n"
          + "\tnew ConfigForms(ctx, {\n\t\tmirror,\n\t\tschema\n\t});\n"
          + "}\n";

  @Test
  public void legacyModuleIsPatchedOnceAndIdempotent() {
    String patched = LanSettingsPatch.apply(V020);
    assertTrue(patched.contains(LanSettingsPatch.AFTER));
    assertFalse(patched.contains(LanSettingsPatch.BEFORE));
    assertEquals(patched, LanSettingsPatch.apply(patched));
  }

  @Test
  public void hostOnlyModuleNeedsNoPatch() {
    assertSame(V021, LanSettingsPatch.apply(V021));
  }

  @Test
  public void unknownShapesAreStillRejected() {
    for (String source :
        new String[] {
          "",
          "const mirror = new SettingsDescribeMirror(ctx, persistence);",
          V021 + V021,
          V021 + "if (this.persistence === \"memory\") return;",
          V021.replace("new SettingsDescribeMirror(ctx);", "new SettingsDescribeMirror(ctx, mode);")
        }) {
      try {
        LanSettingsPatch.apply(source);
        fail(source);
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
