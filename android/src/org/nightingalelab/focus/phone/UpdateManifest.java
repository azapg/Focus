package org.nightingalelab.focus.phone;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Pure-Java manifest parser, GitHub Release JSON inspector, URL/redirect validator,
 * version comparator, and SHA-256 verifier for Android nightly updates.
 * Kept free of android.* imports so it runs in desktop unit tests (PhoneCoreTest).
 */
public final class UpdateManifest {
  public final int version;
  public final String channel;
  public final String platform;
  public final String packageName;
  public final long versionCode;
  public final String versionName;
  public final String buildId;
  public final String commitSha;
  public final String commitSubject;
  public final String publishedAt;
  public final String apkName;
  public final long apkSize;
  public final String apkSha256;
  /** Release notes written by the nightly CI: up to 3 {title, sentence} pairs. Never null. */
  public final List<String[]> notes;
  /** Optional closing line such as "Plus 3 more fixes." Never null. */
  public final String notesMore;

  public static final int MAX_NOTES = 3;
  static final long APK_SANITY_LIMIT = 256L * 1024 * 1024;

  public UpdateManifest(
      int version,
      String channel,
      String platform,
      String packageName,
      long versionCode,
      String versionName,
      String buildId,
      String commitSha,
      String commitSubject,
      String publishedAt,
      String apkName,
      long apkSize,
      String apkSha256) {
    this(version, channel, platform, packageName, versionCode, versionName, buildId, commitSha,
        commitSubject, publishedAt, apkName, apkSize, apkSha256, null, "");
  }

  public UpdateManifest(
      int version,
      String channel,
      String platform,
      String packageName,
      long versionCode,
      String versionName,
      String buildId,
      String commitSha,
      String commitSubject,
      String publishedAt,
      String apkName,
      long apkSize,
      String apkSha256,
      List<String[]> notes,
      String notesMore) {
    this.version = version;
    this.channel = channel;
    this.platform = platform;
    this.packageName = packageName;
    this.versionCode = versionCode;
    this.versionName = versionName;
    this.buildId = buildId;
    this.commitSha = commitSha;
    this.commitSubject = commitSubject;
    this.publishedAt = publishedAt;
    this.apkName = apkName;
    this.apkSize = apkSize;
    this.apkSha256 = apkSha256;
    List<String[]> clean = new ArrayList<>();
    if (notes != null) {
      for (String[] n : notes) {
        if (clean.size() >= MAX_NOTES) break;
        if (n == null || n.length == 0) continue;
        String title = clip(n[0], 80);
        String body = n.length > 1 ? clip(n[1], 240) : "";
        if (title.isEmpty() && body.isEmpty()) continue;
        clean.add(new String[] {title, body});
      }
    }
    this.notes = Collections.unmodifiableList(clean);
    this.notesMore = clip(notesMore, 120);
  }

  /** True when the CI shipped release notes; otherwise the UI falls back to {@link #commitSubject}. */
  public boolean hasNotes() {
    return !notes.isEmpty();
  }

  private static String clip(String s, int max) {
    if (s == null) return "";
    String t = s.trim().replaceAll("\\s+", " ");
    return t.length() <= max ? t : t.substring(0, max - 1).trim() + "\u2026";
  }

  public static UpdateManifest parseManifest(String raw) {
    Object parsed = new MiniJson(raw).parseValue();
    Map<String, Object> root = asObj(parsed, "Invalid manifest JSON");
    int version = (int) reqLong(root, "version");
    if (version != 1) throw new IllegalArgumentException("Unsupported manifest version: " + version);
    String channel = optString(root, "channel", "nightly");
    String platform = reqString(root, "platform");
    if (!"android".equals(platform)) {
      throw new IllegalArgumentException("Unexpected manifest platform: " + platform);
    }
    String packageName = reqString(root, "package_name");
    long versionCode = reqLong(root, "version_code");
    if (versionCode < 1) throw new IllegalArgumentException("Invalid version_code");
    String versionName = optString(root, "version_name", String.valueOf(versionCode));
    String buildId = reqString(root, "build_id");
    String commitSha = reqString(root, "commit_sha").toLowerCase(Locale.ROOT);
    String commitSubject = optString(root, "commit_subject", "");
    String publishedAt = optString(root, "published_at", "");

    Map<String, Object> apk = asObj(root.get("apk"), "Manifest missing apk metadata");
    String apkName = reqString(apk, "name");
    Object size = apk.get("size");
    if (!(size instanceof Long) || (Long) size <= 0) {
      throw new IllegalArgumentException("APK size must be a positive integer in bytes (got " + size + ")");
    }
    long apkSize = (Long) size;
    String apkSha256 = reqString(apk, "sha256").toLowerCase(Locale.ROOT);
    if (!isHex64(apkSha256)) throw new IllegalArgumentException("Invalid APK SHA-256");

    // Optional release notes: {"items": [["Title", "One sentence."]], "more": "Plus 3 more fixes."}.
    // Older manifests have no notes; malformed notes are dropped instead of failing the update check.
    List<String[]> notes = new ArrayList<>();
    String notesMore = "";
    Object notesObj = root.get("notes");
    if (notesObj instanceof Map<?, ?>) {
      @SuppressWarnings("unchecked")
      Map<String, Object> nm = (Map<String, Object>) notesObj;
      Object items = nm.get("items");
      if (items instanceof List<?>) {
        for (Object it : (List<?>) items) {
          if (!(it instanceof List<?>)) continue;
          List<?> pair = (List<?>) it;
          String title = pair.size() > 0 && pair.get(0) instanceof String ? (String) pair.get(0) : "";
          String body = pair.size() > 1 && pair.get(1) instanceof String ? (String) pair.get(1) : "";
          notes.add(new String[] {title, body});
        }
      }
      notesMore = optString(nm, "more", "");
    }

    return new UpdateManifest(
        version,
        channel,
        platform,
        packageName,
        versionCode,
        versionName,
        buildId,
        commitSha,
        commitSubject,
        publishedAt,
        apkName,
        apkSize,
        apkSha256,
        notes,
        notesMore);
  }

  /** Finds a release asset's GitHub API URL (`assets[].url`) by exact file name. */
  public static String findReleaseAssetUrl(String releaseJson, String assetName) {
    Object parsed = new MiniJson(releaseJson).parseValue();
    Map<String, Object> root = asObj(parsed, "Invalid GitHub release JSON");
    Object assetsObj = root.get("assets");
    if (!(assetsObj instanceof List<?>)) return null;
    for (Object item : (List<?>) assetsObj) {
      if (!(item instanceof Map<?, ?>)) continue;
      @SuppressWarnings("unchecked")
      Map<String, Object> asset = (Map<String, Object>) item;
      String name = optString(asset, "name", "");
      if (assetName.equals(name)) {
        String url = optString(asset, "url", "");
        if (!url.isEmpty()) return url;
      }
    }
    return null;
  }

  /**
   * Returns true only when the remote build is a different commit AND Android will accept
   * installing it in place without a downgrade error.
   */
  public static boolean isUpdateAvailable(
      long currentVersionCode,
      String currentCommitSha,
      String currentBuildId,
      UpdateManifest remote) {
    if (remote == null) return false;
    String curSha = currentCommitSha == null ? "" : currentCommitSha.trim().toLowerCase(Locale.ROOT);
    String remSha = remote.commitSha == null ? "" : remote.commitSha.trim().toLowerCase(Locale.ROOT);
    if (!curSha.isEmpty() && !remSha.isEmpty() && curSha.equals(remSha)) {
      return false;
    }
    String curId = currentBuildId == null ? "" : currentBuildId.trim().toLowerCase(Locale.ROOT);
    String remId = remote.buildId == null ? "" : remote.buildId.trim().toLowerCase(Locale.ROOT);
    if (!curId.isEmpty() && !remId.isEmpty() && curId.equals(remId)) {
      return false;
    }
    if (currentVersionCode <= 1) {
      return remote.versionCode >= 1;
    }
    return remote.versionCode > currentVersionCode;
  }

  public static boolean isAllowedApiUrl(String url) {
    return url != null && url.startsWith("https://api.github.com/");
  }

  public static boolean isAllowedDownloadRedirect(String url) {
    return url != null && url.startsWith("https://");
  }

  public static boolean isHex64(String value) {
    if (value == null || value.length() != 64) return false;
    for (int i = 0; i < 64; i++) {
      char c = value.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!ok) return false;
    }
    return true;
  }

  public static String sha256Hex(File file) throws IOException {
    try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
      return sha256Hex(in);
    }
  }

  public static String sha256Hex(InputStream in) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[16 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) {
        md.update(buf, 0, n);
      }
      return toHex(md.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String toHex(byte[] bytes) {
    char[] hex = "0123456789abcdef".toCharArray();
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2] = hex[v >>> 4];
      out[i * 2 + 1] = hex[v & 0x0F];
    }
    return new String(out);
  }

  public static void checkDownloadSize(long expectedSize, long usableSpace) throws IOException {
    if (expectedSize <= 0 || expectedSize > APK_SANITY_LIMIT) {
      throw new IOException("Manifest APK size is " + expectedSize
          + " bytes. Allowed range is 1 to " + APK_SANITY_LIMIT + " bytes.");
    }
    // Keep enough space for the download and the PackageInstaller session copy.
    checkFreeSpace(2 * expectedSize, usableSpace);
  }

  static void checkFreeSpace(long required, long usableSpace) throws IOException {
    if (usableSpace < required) {
      throw new IOException("Update needs " + required + " bytes of free space. Available: "
          + usableSpace + " bytes.");
    }
  }

  public static void checkApkSize(long actualSize, long expectedSize, boolean complete) throws IOException {
    if (actualSize > expectedSize || (complete && actualSize != expectedSize)) {
      throw new IOException("APK size mismatch (got " + actualSize + " bytes, expected "
          + expectedSize + " bytes).");
    }
  }

  public static void verifyFile(File file, long expectedSize, String expectedSha256) throws IOException {
    if (file == null || !file.isFile()) {
      throw new IOException("Downloaded APK file is missing");
    }
    checkApkSize(file.length(), expectedSize, true);
    String actualHash = sha256Hex(file);
    if (!actualHash.equalsIgnoreCase(expectedSha256)) {
      throw new IOException("APK SHA-256 verification failed");
    }
  }

  public static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024L * 1024L) return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
  }

  public String toJsonString() {
    return "{"
        + "\"version\":" + version + ","
        + "\"channel\":" + quote(channel) + ","
        + "\"platform\":" + quote(platform) + ","
        + "\"package_name\":" + quote(packageName) + ","
        + "\"version_code\":" + versionCode + ","
        + "\"version_name\":" + quote(versionName) + ","
        + "\"build_id\":" + quote(buildId) + ","
        + "\"commit_sha\":" + quote(commitSha) + ","
        + "\"commit_subject\":" + quote(commitSubject) + ","
        + "\"published_at\":" + quote(publishedAt) + ","
        + "\"apk\":{"
        + "\"name\":" + quote(apkName) + ","
        + "\"size\":" + apkSize + ","
        + "\"sha256\":" + quote(apkSha256)
        + "}"
        + notesJson()
        + "}";
  }

  private String notesJson() {
    if (notes.isEmpty() && notesMore.isEmpty()) return "";
    StringBuilder sb = new StringBuilder(",\"notes\":{\"items\":[");
    for (int i = 0; i < notes.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('[').append(quote(notes.get(i)[0])).append(',').append(quote(notes.get(i)[1])).append(']');
    }
    sb.append("],\"more\":").append(quote(notesMore)).append('}');
    return sb.toString();
  }

  private static String quote(String s) {
    if (s == null) return "\"\"";
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') sb.append('\\').append(c);
      else if (c == '\n') sb.append("\\n");
      else if (c == '\r') sb.append("\\r");
      else if (c == '\t') sb.append("\\t");
      else if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
      else sb.append(c);
    }
    sb.append('"');
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObj(Object o, String err) {
    if (!(o instanceof Map<?, ?>)) throw new IllegalArgumentException(err);
    return (Map<String, Object>) o;
  }

  private static String reqString(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
      throw new IllegalArgumentException("Missing string field: " + key);
    }
    return ((String) v).trim();
  }

  private static String optString(Map<String, Object> m, String key, String fallback) {
    Object v = m.get(key);
    return v instanceof String ? ((String) v).trim() : fallback;
  }

  private static long reqLong(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v instanceof Number) return ((Number) v).longValue();
    throw new IllegalArgumentException("Missing number field: " + key);
  }

  /** Minimal strict JSON parser for zero-dependency JVM + Android compatibility. */
  private static final class MiniJson {
    private final String s;
    private int i;

    MiniJson(String s) {
      if (s == null) throw new IllegalArgumentException("Empty JSON");
      this.s = s;
    }

    Object parseValue() {
      skipWs();
      if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
      char c = s.charAt(i);
      if (c == '{') return parseObject();
      if (c == '[') return parseArray();
      if (c == '"') return parseString();
      if (c == 't') { expect("true"); return Boolean.TRUE; }
      if (c == 'f') { expect("false"); return Boolean.FALSE; }
      if (c == 'n') { expect("null"); return null; }
      if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
      throw new IllegalArgumentException("Unexpected JSON char at " + i);
    }

    private Map<String, Object> parseObject() {
      i++; // '{'
      Map<String, Object> map = new LinkedHashMap<>();
      skipWs();
      if (i < s.length() && s.charAt(i) == '}') {
        i++;
        return map;
      }
      while (true) {
        skipWs();
        String key = parseString();
        skipWs();
        if (i >= s.length() || s.charAt(i) != ':') {
          throw new IllegalArgumentException("Expected ':' at " + i);
        }
        i++;
        Object val = parseValue();
        map.put(key, val);
        skipWs();
        if (i >= s.length()) throw new IllegalArgumentException("Unterminated object");
        char c = s.charAt(i++);
        if (c == '}') return map;
        if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' at " + (i - 1));
      }
    }

    private List<Object> parseArray() {
      i++; // '['
      List<Object> list = new ArrayList<>();
      skipWs();
      if (i < s.length() && s.charAt(i) == ']') {
        i++;
        return list;
      }
      while (true) {
        list.add(parseValue());
        skipWs();
        if (i >= s.length()) throw new IllegalArgumentException("Unterminated array");
        char c = s.charAt(i++);
        if (c == ']') return list;
        if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' at " + (i - 1));
      }
    }

    private String parseString() {
      if (i >= s.length() || s.charAt(i) != '"') {
        throw new IllegalArgumentException("Expected '\"' at " + i);
      }
      i++;
      StringBuilder sb = new StringBuilder();
      while (i < s.length()) {
        char c = s.charAt(i++);
        if (c == '"') return sb.toString();
        if (c == '\\') {
          if (i >= s.length()) throw new IllegalArgumentException("Unterminated escape");
          char e = s.charAt(i++);
          switch (e) {
            case '"': sb.append('"'); break;
            case '\\': sb.append('\\'); break;
            case '/': sb.append('/'); break;
            case 'b': sb.append('\b'); break;
            case 'f': sb.append('\f'); break;
            case 'n': sb.append('\n'); break;
            case 'r': sb.append('\r'); break;
            case 't': sb.append('\t'); break;
            case 'u':
              if (i + 4 > s.length()) throw new IllegalArgumentException("Invalid unicode escape");
              sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
              i += 4;
              break;
            default:
              throw new IllegalArgumentException("Invalid escape \\" + e);
          }
        } else {
          sb.append(c);
        }
      }
      throw new IllegalArgumentException("Unterminated string");
    }

    private Number parseNumber() {
      int start = i;
      if (s.charAt(i) == '-') i++;
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
      boolean isFloat = false;
      if (i < s.length() && s.charAt(i) == '.') {
        isFloat = true;
        i++;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
      }
      if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
        isFloat = true;
        i++;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
      }
      String num = s.substring(start, i);
      if (isFloat) return Double.valueOf(num);
      return Long.valueOf(num);
    }

    private void expect(String lit) {
      if (!s.startsWith(lit, i)) throw new IllegalArgumentException("Expected " + lit + " at " + i);
      i += lit.length();
    }

    private void skipWs() {
      while (i < s.length()) {
        char c = s.charAt(i);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') i++;
        else break;
      }
    }
  }
}
