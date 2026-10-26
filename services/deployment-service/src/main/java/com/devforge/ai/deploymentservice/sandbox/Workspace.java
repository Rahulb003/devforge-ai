package com.devforge.ai.deploymentservice.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Packs a pipeline step's input as a POSIX ustar archive: the repository under {@code workspace/}
 * and the script as {@code step.sh}.
 *
 * <p>Written here rather than pulling in an archiving library for one format with one writer. Paths
 * are checked again even though git-service validated them: this archive is unpacked by a tar
 * inside the sandbox, and a {@code ../} entry is exactly how an archive writes outside its
 * directory. The sandbox would contain it anyway; the archive should never ask.
 */
final class Workspace {

  private static final int BLOCK = 512;

  private Workspace() {}

  static byte[] archive(Map<String, String> files, String script) {
    var out = new ByteArrayOutputStream();
    directory(out, "workspace/");
    for (var entry : files.entrySet()) {
      file(out, "workspace/" + checked(entry.getKey()), entry.getValue().getBytes(StandardCharsets.UTF_8));
    }
    file(out, "step.sh", script.getBytes(StandardCharsets.UTF_8));
    out.writeBytes(new byte[BLOCK * 2]);
    return out.toByteArray();
  }

  /** A repository path is relative, has no empty, "." or ".." segment, and fits a ustar header. */
  static String checked(String path) {
    if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")
        || path.chars().anyMatch(c -> c < 0x20)) {
      throw new IllegalArgumentException("Unsafe path in workspace: " + path);
    }
    for (var segment : path.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("Unsafe path in workspace: " + path);
      }
    }
    if (("workspace/" + path).getBytes(StandardCharsets.UTF_8).length > 100 + 155) {
      throw new IllegalArgumentException("Path too long for the workspace: " + path);
    }
    return path;
  }

  private static void directory(ByteArrayOutputStream out, String name) {
    out.writeBytes(header(name, 0, '5', 0755));
  }

  private static void file(ByteArrayOutputStream out, String name, byte[] content) {
    out.writeBytes(header(name, content.length, '0', 0644));
    out.writeBytes(content);
    var pad = (BLOCK - content.length % BLOCK) % BLOCK;
    out.writeBytes(new byte[pad]);
  }

  private static byte[] header(String name, long size, char type, int mode) {
    var header = new byte[BLOCK];
    var bytes = name.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= 100) {
      System.arraycopy(bytes, 0, header, 0, bytes.length);
    } else {
      // ustar splits a long name at a slash into prefix (155 bytes) and name (100 bytes).
      var split = name.lastIndexOf('/', name.length() - 2);
      while (split > 0 && name.substring(split + 1).getBytes(StandardCharsets.UTF_8).length > 100) {
        split = name.lastIndexOf('/', split - 1);
      }
      var prefix = name.substring(0, Math.max(split, 0)).getBytes(StandardCharsets.UTF_8);
      var rest = name.substring(split + 1).getBytes(StandardCharsets.UTF_8);
      if (split <= 0 || prefix.length > 155 || rest.length > 100) {
        throw new IllegalArgumentException("Path too long for the workspace: " + name);
      }
      System.arraycopy(rest, 0, header, 0, rest.length);
      System.arraycopy(prefix, 0, header, 345, prefix.length);
    }
    octal(header, 100, 8, mode);
    octal(header, 108, 8, 0);
    octal(header, 116, 8, 0);
    octal(header, 124, 12, size);
    octal(header, 136, 12, 0);
    header[156] = (byte) type;
    System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
    header[263] = '0';
    header[264] = '0';
    // The checksum is computed with its own field read as spaces.
    for (int i = 148; i < 156; i++) {
      header[i] = ' ';
    }
    long sum = 0;
    for (var b : header) {
      sum += b & 0xff;
    }
    octal(header, 148, 7, sum);
    header[155] = ' ';
    return header;
  }

  private static void octal(byte[] header, int offset, int length, long value) {
    var digits = Long.toOctalString(value);
    var padded = "0".repeat(Math.max(0, length - 1 - digits.length())) + digits;
    var bytes = padded.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length - 1));
  }
}
