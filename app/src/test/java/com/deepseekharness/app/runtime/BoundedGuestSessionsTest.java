package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BoundedGuestSessionsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void retainedPreDispatchFailureKeepsExplicitCompletionForItsOwner() throws Exception {
    File files = temporary.newFolder("retained-not-dispatched");
    var fs = new JvmBackupFileSystem();
    var operation = BoundedGuestSessions.begin(fs, files);
    operation.nativeProtocol();
    operation.retainCompletion();
    operation.completedBeforeDispatch();
    org.junit.Assert.assertFalse(
        new File(files, "bounded-guest-active/" + operation.id()).exists());
    org.junit.Assert.assertTrue(BoundedGuestSessions.recordedCompletion(fs, files, operation.id()));
    assertNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void preDispatchCompletionCannotBeReusedForAnotherOwner() throws Exception {
    File files = temporary.newFolder("wrong-pre-dispatch-owner");
    var fs = new JvmBackupFileSystem();
    var operation = BoundedGuestSessions.begin(fs, files);
    operation.retainCompletion();
    operation.completedBeforeDispatch();
    String other = UUID.randomUUID().toString();
    File completed = new File(files, "bounded-guest-completed");
    write(
        fs,
        new File(completed, other),
        "DSHA_OWNED_TREE_V2_NOT_DISPATCHED\n" + operation.id() + "\nLAUNCHER_ABSENT\n");
    assertThrows(
        IOException.class, () -> BoundedGuestSessions.recordedCompletion(fs, files, other));
  }

  @Test
  public void v2ClosedTreeUsesOriginalBirthExitWhileOnlyLegacyUsesSidEmptiness() throws Exception {
    File files = temporary.newFolder("v2-birth");
    var fs = new JvmBackupFileSystem();
    var operation = BoundedGuestSessions.begin(fs, files);
    operation.nativeProtocol();
    operation.uncertain();
    File entry = new File(files, "bounded-guest-active/" + operation.id());
    write(fs, new File(entry, "identity"), operation.id() + "\n123 456 123\n");
    operation.treeProof(7);
    BoundedGuestSessions.reapExited(
        fs,
        files,
        (pid, born) -> {
          throw new AssertionError("SID is not V2 closure evidence");
        },
        (pid, born) -> true);
    org.junit.Assert.assertFalse(entry.exists());
  }

  @Test
  public void impossibleExitReceiptCannotRetireV2() throws Exception {
    File files = temporary.newFolder("bad-code");
    var fs = new JvmBackupFileSystem();
    var operation = BoundedGuestSessions.begin(fs, files);
    operation.nativeProtocol();
    operation.uncertain();
    File entry = new File(files, "bounded-guest-active/" + operation.id());
    String birth = operation.id() + "\n123 456 123\n";
    write(fs, new File(entry, "identity"), birth);
    write(fs, new File(entry, "tree-empty"), birth + "ECHILD 999\n");
    assertThrows(
        IOException.class, () -> BoundedGuestSessions.reapExited(fs, files, (pid, born) -> true));
    org.junit.Assert.assertTrue(entry.exists());
  }

  @Test
  public void recordedOwnerKeepsDurableClosedProofAfterActiveRecordRetirement() throws Exception {
    File files = temporary.newFolder("retained-proof");
    var fs = new JvmBackupFileSystem();
    var operation = BoundedGuestSessions.begin(fs, files);
    operation.nativeProtocol();
    operation.retainCompletion();
    File entry = new File(files, "bounded-guest-active/" + operation.id());
    write(fs, new File(entry, "identity"), operation.id() + "\n123 456 123\n");
    operation.treeProof(7);
    operation.completed();
    org.junit.Assert.assertFalse(entry.exists());
    org.junit.Assert.assertTrue(BoundedGuestSessions.recordedCompletion(fs, files, operation.id()));
    assertNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void v2MissingTreeReceiptCannotBeRetiredByAnEmptySidClaim() throws Exception {
    File files = temporary.newFolder("v2");
    var fs = new JvmBackupFileSystem();
    var operation = BoundedGuestSessions.begin(fs, files);
    operation.nativeProtocol();
    operation.uncertain();
    File entry = new File(files, "bounded-guest-active/" + operation.id());
    write(fs, new File(entry, "identity"), operation.id() + "\n123 456 123\n");
    assertThrows(
        IOException.class, () -> BoundedGuestSessions.reapExited(fs, files, (pid, born) -> true));
    org.junit.Assert.assertTrue(entry.exists());
    operation.treeProof(7);
    BoundedGuestSessions.reapExited(fs, files, (pid, born) -> true);
    org.junit.Assert.assertFalse(entry.exists());
  }

  @Test
  public void inventoryReportsEveryRecordAndALaterInvalidRecordStillBlocks() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    var a = BoundedGuestSessions.begin(fs, files);
    var b = BoundedGuestSessions.begin(fs, files);
    a.uncertain();
    b.uncertain();
    fs.create(new File(files, "bounded-guest-active/" + b.id() + "/unexpected")).close();
    org.junit.Assert.assertEquals(2, BoundedGuestSessions.inspect(fs, files).size());
    org.junit.Assert.assertTrue(
        BoundedGuestSessions.inspect(fs, files).stream()
            .anyMatch(i -> i.state().equals("INVALID")));
    assertNotNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void inProcessCommandIsIgnoredButUncertainRecordBlocksStartup() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    assertNull(BoundedGuestSessions.firstPending(fs, files));
    operation.uncertain();
    assertNotNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void unknownRecordMemberCannotBeSilentlyRetired() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    operation.uncertain();
    String id = BoundedGuestSessions.firstPending(fs, files);
    File entry = new File(new File(files, "bounded-guest-active"), id);
    fs.create(new File(entry, "unexpected")).close();
    assertThrows(IOException.class, () -> BoundedGuestSessions.reapExited(fs, files));
  }

  @Test
  public void interruptedIntentPublicationBeforeHandshakeCanBeReaped() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    File home = new File(files, "bounded-guest-active");
    fs.directory(home);
    String id = UUID.randomUUID().toString();
    File entry = new File(home, id);
    fs.directory(entry);
    write(fs, new File(entry, "intent.tmp-" + UUID.randomUUID()), id.substring(0, 12));
    assertNotNull(BoundedGuestSessions.firstPending(fs, files));
    BoundedGuestSessions.reapExited(fs, files);
    assertNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void interruptedIdentityPublicationBeforeHandshakeCanBeReaped() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    operation.uncertain();
    String id = BoundedGuestSessions.firstPending(fs, files);
    File entry = new File(new File(files, "bounded-guest-active"), id);
    write(fs, new File(entry, "identity.tmp-" + UUID.randomUUID()), id + "\n123 456 ");
    BoundedGuestSessions.reapExited(fs, files);
    assertNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void cleanupInterruptedAfterIdentityUnlinkRetiresOnlyKnownMembers() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    operation.uncertain();
    String id = BoundedGuestSessions.firstPending(fs, files);
    File entry = new File(new File(files, "bounded-guest-active"), id);
    File identity = new File(entry, "identity");
    write(fs, identity, id + "\n123 456 123\n");
    fs.delete(identity); // The group was already confirmed gone before cleanup began.
    write(fs, new File(entry, "identity.tmp-" + UUID.randomUUID()), id + "\n123 ");
    BoundedGuestSessions.reapExited(fs, files);
    assertNull(BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void nonFileInKnownTemporarySlotStillBlocksRecovery() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    BoundedGuestSessions.Operation operation = BoundedGuestSessions.begin(fs, files);
    operation.uncertain();
    String id = BoundedGuestSessions.firstPending(fs, files);
    File entry = new File(new File(files, "bounded-guest-active"), id);
    fs.directory(new File(entry, "identity.tmp-" + UUID.randomUUID()));
    assertThrows(IOException.class, () -> BoundedGuestSessions.reapExited(fs, files));
  }

  @Test
  public void corruptRecordIsRetainedWhileLaterProvenEmptyRecordIsPruned() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    var bad = BoundedGuestSessions.begin(fs, files);
    var good = BoundedGuestSessions.begin(fs, files);
    bad.uncertain();
    good.uncertain();
    File home = new File(files, "bounded-guest-active");
    write(fs, new File(home, bad.id() + "/identity"), "bad identity\n");
    write(fs, new File(home, good.id() + "/identity"), good.id() + "\n123 456 123\n");
    IOException blocked =
        assertThrows(
            IOException.class,
            () -> BoundedGuestSessions.reapExited(fs, files, (pid, born) -> true));
    org.junit.Assert.assertTrue(blocked.getMessage().contains(bad.id()));
    org.junit.Assert.assertTrue(new File(home, bad.id()).isDirectory());
    org.junit.Assert.assertFalse(new File(home, good.id()).exists());
    org.junit.Assert.assertEquals(bad.id(), BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void cancelledReapingDoesNotContinueIntoLaterRecords() throws Exception {
    File files = temporary.newFolder("files");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    var first = BoundedGuestSessions.begin(fs, files);
    var later = BoundedGuestSessions.begin(fs, files);
    first.uncertain();
    later.uncertain();
    File home = new File(files, "bounded-guest-active");
    write(fs, new File(home, first.id() + "/identity"), first.id() + "\n123 456 123\n");
    write(fs, new File(home, later.id() + "/identity"), later.id() + "\n124 457 124\n");
    assertThrows(
        java.io.InterruptedIOException.class,
        () ->
            BoundedGuestSessions.reapExited(
                fs,
                files,
                (pid, born) -> {
                  throw new java.io.InterruptedIOException("CANCELLED");
                }));
    org.junit.Assert.assertTrue(new File(home, first.id()).isDirectory());
    org.junit.Assert.assertTrue(new File(home, later.id()).isDirectory());
  }

  @Test
  public void maintenanceReapExplainsTheRebootInsteadOfReportingNothingToRecover()
      throws Exception {
    File files = temporary.newFolder("boot-boundary");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    String id = manualV2Session(fs, files);
    String retained =
        BoundedGuestSessions.reapExitedTolerant(
            fs, files, (pid, born) -> true, (pid, born) -> true, () -> BOOT_A);
    assertNotNull(retained);
    org.junit.Assert.assertTrue(
        retained.contains("需重启手机") || retained.contains("Restart the phone"));
    org.junit.Assert.assertFalse(retained.startsWith("BOUNDED_GUEST_RECORDS_RETAINED"));
    org.junit.Assert.assertEquals(id, BoundedGuestSessions.firstPending(fs, files));
  }

  @Test
  public void maintenanceReapClosesTheBootJournalOnlyAfterARealBoot() throws Exception {
    File files = temporary.newFolder("boot-boundary-closed");
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    String id = manualV2Session(fs, files);
    assertNotNull(
        BoundedGuestSessions.reapExitedTolerant(
            fs, files, (pid, born) -> true, (pid, born) -> true, () -> BOOT_A));
    assertNull(
        BoundedGuestSessions.reapExitedTolerant(
            fs, files, (pid, born) -> true, (pid, born) -> true, () -> BOOT_B));
    assertNull(BoundedGuestSessions.firstPending(fs, files));
    org.junit.Assert.assertTrue(
        new File(files, "bounded-guest-boot-recovery/" + id + "/closed").isFile());
  }

  @Test
  public void quiescenceAcceptsOnlyZygoteChildrenRunningAppProcess() throws Exception {
    File proc = temporary.newFolder("proc-quiet");
    pid(proc, 101, "S 42 0 0");
    pid(proc, 102, "Z 1 0 0");
    org.junit.Assert.assertTrue(
        BoundedGuestSessions.appUidQuiescent(
            proc, 4242, 42, (entry) -> 4242, (entry) -> "/system/bin/app_process64"));
  }

  @Test
  public void quiescenceRejectsAGuestThatNamesItselfLikeTheApp() throws Exception {
    File proc = temporary.newFolder("proc-spoof");
    pid(proc, 101, "S 42 0 0");
    // Same argv[0] as the app, but the guest neither came from zygote nor runs app_process.
    pid(proc, 103, "S 1 0 0");
    org.junit.Assert.assertFalse(
        BoundedGuestSessions.appUidQuiescent(
            proc,
            4242,
            42,
            (entry) -> 4242,
            (entry) ->
                entry.getName().equals("103") ? "/system/bin/bash" : "/system/bin/app_process64"));
  }

  @Test
  public void quiescenceRejectsAProcessThatLostTheZygoteParent() throws Exception {
    File proc = temporary.newFolder("proc-reparented");
    pid(proc, 101, "S 42 0 0");
    pid(proc, 104, "S 1 0 0");
    org.junit.Assert.assertFalse(
        BoundedGuestSessions.appUidQuiescent(
            proc, 4242, 42, (entry) -> 4242, (entry) -> "/system/bin/app_process64"));
  }

  @Test
  public void quiescenceFailsClosedWhenTheExecutableIsUnreadable() throws Exception {
    File proc = temporary.newFolder("proc-unreadable");
    pid(proc, 101, "S 42 0 0");
    org.junit.Assert.assertFalse(
        BoundedGuestSessions.appUidQuiescent(proc, 4242, 42, (entry) -> 4242, (entry) -> null));
  }

  @Test
  public void quiescenceIgnoresOtherUidsAndPropagatesUnreadableOwners() throws Exception {
    File proc = temporary.newFolder("proc-owners");
    pid(proc, 101, "S 42 0 0");
    pid(proc, 105, "S 1 0 0");
    org.junit.Assert.assertTrue(
        BoundedGuestSessions.appUidQuiescent(
            proc,
            4242,
            42,
            (entry) -> entry.getName().equals("105") ? 20000 : 4242,
            (entry) -> "/system/bin/app_process64"));
    assertThrows(
        IOException.class,
        () ->
            BoundedGuestSessions.appUidQuiescent(
                proc,
                4242,
                42,
                (entry) -> {
                  throw new IOException("OWNER_DENIED");
                },
                (entry) -> "/system/bin/app_process64"));
  }

  private static void pid(File proc, int pid, String tail) throws IOException {
    File directory = new File(proc, Integer.toString(pid));
    if (!directory.mkdir()) throw new IOException("PID_DIR");
    try (var out = new java.io.FileWriter(new File(directory, "stat"))) {
      out.write(pid + " (process) " + tail + " 0 0 0 0 0\n");
    }
  }

  private static final String BOOT_A = "11111111-1111-1111-1111-111111111111\n";
  private static final String BOOT_B = "22222222-2222-2222-2222-222222222222\n";

  private static String manualV2Session(JvmBackupFileSystem fs, File files) throws IOException {
    File home = new File(files, "bounded-guest-active");
    fs.directory(home);
    String id = UUID.randomUUID().toString();
    File entry = new File(home, id);
    fs.directory(entry);
    write(fs, new File(entry, "intent"), id + "\n");
    write(fs, new File(entry, "protocol"), "DSHA_OWNED_TREE_V2\n");
    write(fs, new File(entry, "identity"), id + "\n123 456 123\n");
    return id;
  }

  private static void write(JvmBackupFileSystem fs, File file, String value) throws IOException {
    try (var out = fs.create(file)) {
      out.write(value.getBytes(StandardCharsets.US_ASCII));
    }
  }
}
