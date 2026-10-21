package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** A reboot closes old processes, not their commands. Never emits an ECHILD or task receipt. */
final class BoundedGuestBootRecovery {
  static final String HOME = "bounded-guest-boot-recovery";
  private static final String UUID = com.deepseekharness.app.util.Ids.UUID_PATTERN;
  private static final String CHECKPOINT = "DSHA_BOOT_BOUNDARY_CHECKPOINT_V1";
  private static final String CLOSED = "DSHA_BOOT_BOUNDARY_CLOSED_V1";
  // Same-boot closure: no process of this app UID survived, so the recorded tree cannot exist.
  private static final String QUIESCENT = "DSHA_UID_QUIESCENT_CLOSED_V1";
  private static final int LIMIT = 8192;
  private static final List<String> MEMBERS = List.of("identity", "intent", "protocol");

  /** Newer retained records add this member; the canonical snapshot must accept it. */
  private static final Set<String> OPTIONAL_MEMBERS = Set.of("retain");

  interface BootSource {
    String read() throws IOException;
  }

  interface BindingGuard {
    void requireUnbound(String id) throws IOException;
  }

  /**
   * Same-boot alternative to a real reboot (#28). Every guest process runs under this app UID, so
   * when the only processes of the UID left are the app's own zygote processes, the recorded tree
   * is gone. Android force-stop kills the whole process group, which is the usual way a record is
   * left without its exit receipt. Anything unreadable or alive keeps the reboot requirement.
   */
  interface Quiescence {
    boolean confirmed() throws IOException;
  }

  static final Quiescence NEVER = () -> false;

  private record Checkpoint(String id, String boot, String digest, byte[] snapshot) {}

  private BoundedGuestBootRecovery() {}

  static String boot(BootSource source) throws IOException {
    String value;
    try {
      value = source.read();
    } catch (InterruptedIOException cancelled) {
      throw cancelled;
    } catch (IOException unavailable) {
      throw new IOException("BOUNDED_GUEST_BOOT_ID_UNAVAILABLE", unavailable);
    }
    if (value == null || !value.matches(UUID + "\n"))
      throw new IOException("BOUNDED_GUEST_BOOT_ID_UNTRUSTED");
    return value.substring(0, 36);
  }

  /** Read-only inventory: an original moved before proof publication still blocks every caller. */
  static List<String> pending(BackupFileSystem fs, File files) throws IOException {
    List<String> result = new ArrayList<>();
    for (String id : ids(fs, files)) {
      File journal = journal(fs, files, id);
      validateJournal(fs, journal);
      File original = fs.child(journal, "original");
      File closed = fs.child(journal, "closed");
      boolean active = !fs.stat(active(fs, files, id)).type.equals("MISSING");
      if (fs.stat(closed).type.equals("MISSING")) {
        result.add(id);
        continue;
      }
      Checkpoint checkpoint = checkpoint(fs, journal, id);
      if (active || !Arrays.equals(checkpoint.snapshot, capture(fs, original, id)))
        throw new IOException("BOUNDED_GUEST_BOOT_ORIGINAL_CHANGED");
      validateClosed(fs, journal, checkpoint);
    }
    return result;
  }

  /** Only invoked by the existing explicit maintenance reap fence, never by inventory/startup. */
  static List<String> recoverPreserved(
      BackupFileSystem fs, File files, BootSource boots, BindingGuard guard) throws IOException {
    return recoverPreserved(fs, files, boots, guard, NEVER);
  }

  static List<String> recoverPreserved(
      BackupFileSystem fs, File files, BootSource boots, BindingGuard guard, Quiescence quiet)
      throws IOException {
    cancelled();
    List<String> blocked = new ArrayList<>();
    for (String id : ids(fs, files)) {
      try {
        cancelled();
        File journal = journal(fs, files, id);
        validateJournal(fs, journal);
        if (fs.stat(fs.child(journal, "original")).type.equals("MISSING")) continue;
        if (!fs.stat(active(fs, files, id)).type.equals("MISSING"))
          throw new IOException("BOUNDED_GUEST_BOOT_LOCATION_CONFLICT");
        finish(fs, files, journal, checkpoint(fs, journal, id), boots, guard, quiet);
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException failure) {
        blocked.add(id + ":" + failure.getMessage());
      }
    }
    return blocked;
  }

  static void recover(
      BackupFileSystem fs, File files, File entry, String id, BootSource boots, BindingGuard guard)
      throws IOException {
    recover(fs, files, entry, id, boots, guard, NEVER);
  }

  static void recover(
      BackupFileSystem fs,
      File files,
      File entry,
      String id,
      BootSource boots,
      BindingGuard guard,
      Quiescence quiet)
      throws IOException {
    cancelled();
    if (!id.matches(UUID) || !entry.equals(active(fs, files, id)))
      throw new IOException("BOUNDED_GUEST_BOOT_RECORD_PATH");
    guard.requireUnbound(id);
    byte[] snapshot = capture(fs, entry, id);
    String currentBoot = boot(boots);
    File root = fs.child(files, HOME);
    directory(fs, root);
    File journal = fs.child(root, id);
    directory(fs, journal);
    validateJournal(fs, journal);
    if (!fs.stat(fs.child(journal, "original")).type.equals("MISSING")
        || !fs.stat(fs.child(journal, "closed")).type.equals("MISSING"))
      throw new IOException("BOUNDED_GUEST_BOOT_LOCATION_CONFLICT");
    File record = fs.child(journal, "checkpoint");
    if (fs.stat(record).type.equals("MISSING")) {
      byte[] value =
          ascii(
              CHECKPOINT
                  + "\n"
                  + id
                  + "\n"
                  + currentBoot
                  + "\n"
                  + digest(snapshot)
                  + "\n"
                  + Base64.getEncoder().encodeToString(snapshot)
                  + "\n");
      if (!currentBoot.equals(boot(boots)) || !Arrays.equals(snapshot, capture(fs, entry, id)))
        throw new IOException("BOUNDED_GUEST_BOOT_RECORD_CHANGED");
      publish(fs, journal, "checkpoint", value);
      sync(fs, files, journal);
      // Falls through: the same-boot check below still demands a reboot or UID quiescence.
    }
    Checkpoint checkpoint = checkpoint(fs, journal, id);
    if (!Arrays.equals(snapshot, checkpoint.snapshot))
      throw new IOException("BOUNDED_GUEST_BOOT_ORIGINAL_CHANGED");
    sync(fs, files, journal);
    boolean sameBoot = checkpoint.boot.equals(currentBoot);
    if (sameBoot && !quiescent(quiet))
      throw new IOException("BOUNDED_GUEST_REAL_REBOOT_REQUIRED:" + id);
    // Checkpoint and its parents must be durable before removing the active pathname.
    guard.requireUnbound(id);
    if (!currentBoot.equals(boot(boots)) || !Arrays.equals(snapshot, capture(fs, entry, id)))
      throw new IOException("BOUNDED_GUEST_BOOT_RECORD_CHANGED");
    // Re-prove quiescence immediately before the active pathname moves.
    if (sameBoot && !quiescent(quiet))
      throw new IOException("BOUNDED_GUEST_REAL_REBOOT_REQUIRED:" + id);
    cancelled();
    fs.move(entry, fs.child(journal, "original"));
    fs.syncDirectory(entry.getParentFile());
    sync(fs, files, journal);
    finish(fs, files, journal, checkpoint, boots, guard, quiet);
  }

  private static void finish(
      BackupFileSystem fs,
      File files,
      File journal,
      Checkpoint checkpoint,
      BootSource boots,
      BindingGuard guard,
      Quiescence quiet)
      throws IOException {
    cancelled();
    if (!fs.stat(active(fs, files, checkpoint.id)).type.equals("MISSING")
        || !Arrays.equals(
            checkpoint.snapshot, capture(fs, fs.child(journal, "original"), checkpoint.id)))
      throw new IOException("BOUNDED_GUEST_BOOT_ORIGINAL_CHANGED");
    if (!fs.stat(fs.child(journal, "closed")).type.equals("MISSING")) {
      validateClosed(fs, journal, checkpoint);
      sync(fs, files, journal);
      return;
    }
    guard.requireUnbound(checkpoint.id);
    String currentBoot = boot(boots);
    boolean sameBoot = checkpoint.boot.equals(currentBoot);
    if (sameBoot && !quiescent(quiet))
      throw new IOException("BOUNDED_GUEST_REAL_REBOOT_REQUIRED:" + checkpoint.id);
    if (!currentBoot.equals(boot(boots))) throw new IOException("BOUNDED_GUEST_BOOT_ID_CHANGED");
    publish(
        fs,
        journal,
        "closed",
        ascii(
            (sameBoot ? QUIESCENT : CLOSED)
                + "\n"
                + checkpoint.id
                + "\n"
                + checkpoint.boot
                + "\n"
                + currentBoot
                + "\n"
                + checkpoint.digest
                + "\n"));
    sync(fs, files, journal);
  }

  private static void validateClosed(BackupFileSystem fs, File journal, Checkpoint checkpoint)
      throws IOException {
    String[] fields = text(fs, fs.child(journal, "closed")).split("\n", -1);
    boolean quiescent = fields.length == 6 && fields[0].equals(QUIESCENT);
    if (fields.length != 6
        || !(fields[0].equals(CLOSED) || quiescent)
        || !fields[1].equals(checkpoint.id)
        || !fields[2].equals(checkpoint.boot)
        || !fields[3].matches(UUID)
        || !quiescent && fields[2].equals(fields[3])
        || !fields[4].equals(checkpoint.digest)
        || !fields[5].isEmpty()) throw new IOException("BOUNDED_GUEST_BOOT_PROOF_INVALID");
  }

  private static Checkpoint checkpoint(BackupFileSystem fs, File journal, String id)
      throws IOException {
    String[] fields = text(fs, fs.child(journal, "checkpoint")).split("\n", -1);
    if (fields.length != 6
        || !fields[0].equals(CHECKPOINT)
        || !fields[1].equals(id)
        || !fields[2].matches(UUID)
        || !fields[3].matches("[a-f0-9]{64}")
        || !fields[5].isEmpty()) throw new IOException("BOUNDED_GUEST_BOOT_CHECKPOINT_INVALID");
    try {
      byte[] bytes = Base64.getDecoder().decode(fields[4]);
      if (bytes.length > LIMIT
          || !Base64.getEncoder().encodeToString(bytes).equals(fields[4])
          || !digest(bytes).equals(fields[3]))
        throw new IOException("BOUNDED_GUEST_BOOT_CHECKPOINT_INVALID");
      return new Checkpoint(id, fields[2], fields[3], bytes);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("BOUNDED_GUEST_BOOT_CHECKPOINT_INVALID", invalid);
    }
  }

  private static byte[] capture(BackupFileSystem fs, File entry, String id) throws IOException {
    BackupFileSystem.Node directory = fs.stat(entry);
    if (!directory.type.equals("DIRECTORY"))
      throw new IOException("BOUNDED_GUEST_BOOT_INELIGIBLE_MEMBERS");
    Set<String> names = new HashSet<>(fs.list(entry));
    if (!names.containsAll(MEMBERS)
        || !names.stream()
            .filter(name -> !MEMBERS.contains(name))
            .allMatch(OPTIONAL_MEMBERS::contains))
      throw new IOException("BOUNDED_GUEST_BOOT_INELIGIBLE_MEMBERS");
    Map<String, byte[]> values = new TreeMap<>();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeUTF("DSHA_BOOT_ORIGINAL_V1");
      identity(out, directory, false);
      for (String name : MEMBERS) {
        File file = fs.child(entry, name);
        BackupFileSystem.Node node = fs.stat(file);
        byte[] value = fs.small(file, name.equals("identity") ? 128 : 64);
        if (!node.same(fs.stat(file))) throw new IOException("BOUNDED_GUEST_BOOT_RECORD_CHANGED");
        values.put(name, value);
        out.writeUTF(name);
        identity(out, node, true);
        out.writeInt(value.length);
        out.write(value);
      }
    }
    String birth = new String(values.get("identity"), StandardCharsets.US_ASCII);
    String[] lines = birth.split("\n", -1);
    if (lines.length != 3
        || !lines[0].equals(id)
        || !lines[2].isEmpty()
        || !lines[1].matches("[1-9][0-9]* [1-9][0-9]* [1-9][0-9]*")
        || !Arrays.equals(values.get("intent"), ascii(id + "\n"))
        || !Arrays.equals(values.get("protocol"), ascii("DSHA_OWNED_TREE_V2\n")))
      throw new IOException("BOUNDED_GUEST_BOOT_RECORD_INVALID");
    if (names.contains("retain")
        && !Arrays.equals(fs.small(fs.child(entry, "retain"), 64), ascii("RECORDED_OWNER\n")))
      throw new IOException("BOUNDED_GUEST_BOOT_RECORD_INVALID");
    try {
      String[] fields = lines[1].split(" ");
      if (Integer.parseInt(fields[0]) < 2
          || Long.parseLong(fields[1]) < 1
          || !fields[0].equals(fields[2])) throw new NumberFormatException();
    } catch (NumberFormatException invalid) {
      throw new IOException("BOUNDED_GUEST_BOOT_RECORD_INVALID", invalid);
    }
    if (!directory.same(fs.stat(entry))
        || !new HashSet<>(fs.list(entry)).equals(new HashSet<>(MEMBERS)))
      throw new IOException("BOUNDED_GUEST_BOOT_RECORD_CHANGED");
    return bytes.toByteArray();
  }

  private static void identity(DataOutputStream out, BackupFileSystem.Node node, boolean file)
      throws IOException {
    if (node.key == null
        || node.key.length() > 256
        || !(file ? "FILE" : "DIRECTORY").equals(node.type))
      throw new IOException("BOUNDED_GUEST_BOOT_RECORD_TYPE");
    out.writeUTF(node.key);
    out.writeLong(node.device);
    out.writeInt(node.mode);
    if (file) {
      out.writeLong(node.size);
      out.writeLong(node.modified);
    }
  }

  private static List<String> ids(BackupFileSystem fs, File files) throws IOException {
    File root = fs.child(files, HOME);
    if (fs.stat(root).type.equals("MISSING")) return List.of();
    if (!fs.stat(root).type.equals("DIRECTORY")) throw new IOException("BOUNDED_GUEST_BOOT_ROOT");
    List<String> ids = fs.list(root);
    // Closed originals are permanent history, not a lifetime launch budget.
    for (String id : ids)
      if (!id.matches(UUID)) throw new IOException("BOUNDED_GUEST_BOOT_RECORD_ID");
    return ids;
  }

  private static File journal(BackupFileSystem fs, File files, String id) throws IOException {
    return fs.child(fs.child(files, HOME), id);
  }

  private static File active(BackupFileSystem fs, File files, String id) throws IOException {
    File home = fs.child(files, "bounded-guest-active");
    String kind = fs.stat(home).type;
    if (!kind.equals("MISSING") && !kind.equals("DIRECTORY"))
      throw new IOException("BOUNDED_GUEST_RECORD_ROOT");
    return kind.equals("MISSING") ? new File(home, id) : fs.child(home, id);
  }

  private static void validateJournal(BackupFileSystem fs, File journal) throws IOException {
    if (!fs.stat(journal).type.equals("DIRECTORY"))
      throw new IOException("BOUNDED_GUEST_BOOT_RECORD_TYPE");
    List<String> names = fs.list(journal);
    for (String name : names) {
      if (name.equals("original")) {
        if (!fs.stat(fs.child(journal, name)).type.equals("DIRECTORY"))
          throw new IOException("BOUNDED_GUEST_BOOT_ORIGINAL_TYPE");
      } else if (name.equals("checkpoint")
          || name.equals("closed")
          || name.matches("(?:checkpoint|closed)\\.tmp-" + UUID)) {
        byte[] bytes = fs.small(fs.child(journal, name), LIMIT);
        if (name.contains(".tmp-")) temporary(journal.getName(), name, bytes);
      } else throw new IOException("BOUNDED_GUEST_BOOT_UNKNOWN_MEMBER");
    }
  }

  private static void directory(BackupFileSystem fs, File directory) throws IOException {
    if (fs.stat(directory).type.equals("MISSING")) fs.directory(directory);
    if (!fs.stat(directory).type.equals("DIRECTORY"))
      throw new IOException("BOUNDED_GUEST_BOOT_ROOT");
  }

  private static void publish(BackupFileSystem fs, File journal, String name, byte[] value)
      throws IOException {
    cancelled();
    File target = fs.child(journal, name);
    if (fs.stat(target).type.equals("MISSING")) fs.atomic(journal, name, value);
    if (!Arrays.equals(value, fs.small(target, LIMIT)))
      throw new IOException("BOUNDED_GUEST_BOOT_RECORD_CHANGED");
    fs.syncDirectory(journal);
  }

  private static void sync(BackupFileSystem fs, File files, File journal) throws IOException {
    fs.syncDirectory(journal);
    fs.syncDirectory(journal.getParentFile());
    fs.syncDirectory(files);
  }

  private static String text(BackupFileSystem fs, File file) throws IOException {
    return new String(fs.small(file, LIMIT), StandardCharsets.US_ASCII);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  /** A crash can leave an empty/partial atomic file; only a typed record prefix is owned. */
  private static void temporary(String id, String name, byte[] bytes) throws IOException {
    String value = new String(bytes, StandardCharsets.US_ASCII);
    if (!Arrays.equals(bytes, ascii(value)))
      throw new IOException("BOUNDED_GUEST_BOOT_TEMP_INVALID");
    String header = null;
    for (String kind :
        name.startsWith("checkpoint.") ? List.of(CHECKPOINT) : List.of(CLOSED, QUIESCENT)) {
      String candidate = kind + "\n" + id + "\n";
      if (candidate.startsWith(value)) return;
      if (value.startsWith(candidate)) header = candidate;
    }
    if (header == null) throw new IOException("BOUNDED_GUEST_BOOT_TEMP_INVALID");
    String[] fields = value.substring(header.length()).split("\n", -1);
    int count = 3;
    if (fields.length > count + 1 || fields.length == count + 1 && !fields[count].isEmpty())
      throw new IOException("BOUNDED_GUEST_BOOT_TEMP_INVALID");
    for (int i = 0; i < Math.min(fields.length, count); i++) {
      boolean complete = i < fields.length - 1;
      String field = fields[i];
      boolean uuid = i == 0 || !name.startsWith("checkpoint.") && i == 1;
      boolean hash = name.startsWith("checkpoint.") ? i == 1 : i == 2;
      if (uuid) {
        String shape = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx";
        if (field.length() > 36 || complete && field.length() != 36)
          throw new IOException("BOUNDED_GUEST_BOOT_TEMP_INVALID");
        for (int n = 0; n < field.length(); n++)
          if (shape.charAt(n) == '-'
              ? field.charAt(n) != '-'
              : "0123456789abcdef".indexOf(field.charAt(n)) < 0)
            throw new IOException("BOUNDED_GUEST_BOOT_TEMP_INVALID");
      } else if (hash) {
        if (!field.matches("[a-f0-9]{0,64}") || complete && field.length() != 64)
          throw new IOException("BOUNDED_GUEST_BOOT_TEMP_INVALID");
      } else if (!field.matches("[A-Za-z0-9+/]*={0,2}")) {
        throw new IOException("BOUNDED_GUEST_BOOT_TEMP_INVALID");
      }
    }
    if (name.startsWith("checkpoint.") && fields.length == 4) {
      try {
        byte[] snapshot = Base64.getDecoder().decode(fields[2]);
        if (!Base64.getEncoder().encodeToString(snapshot).equals(fields[2])
            || !digest(snapshot).equals(fields[1]))
          throw new IOException("BOUNDED_GUEST_BOOT_TEMP_INVALID");
      } catch (IllegalArgumentException invalid) {
        throw new IOException("BOUNDED_GUEST_BOOT_TEMP_INVALID", invalid);
      }
    }
  }

  /** Unreadable or failing evidence never proves quiescence; cancellation still propagates. */
  private static boolean quiescent(Quiescence quiet) throws IOException {
    cancelled();
    if (quiet == null) return false;
    try {
      return quiet.confirmed();
    } catch (InterruptedIOException cancelled) {
      throw cancelled;
    } catch (IOException | RuntimeException unproven) {
      return false;
    }
  }

  private static void cancelled() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CANCELLED");
  }

  private static String digest(byte[] value) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
      StringBuilder result = new StringBuilder(64);
      for (byte part : hash)
        result
            .append(Character.forDigit((part >>> 4) & 15, 16))
            .append(Character.forDigit(part & 15, 16));
      return result.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
