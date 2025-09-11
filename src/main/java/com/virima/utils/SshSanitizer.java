package com.virima.utils;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** SSH output cleaner: strips ANSI/VT100 junk + cooks backspaces/carriage-returns. */
public final class SshSanitizer {
  private SshSanitizer() {}

  // --- Public APIs ------------------------------------------------------------

  /** Clean a full SSH output string (simple + great for small/finite outputs). */
  public static String clean(String raw) {
    if (raw == null || raw.isEmpty()) return raw;
    String s = stripAnsiAll(raw);
    s = stripControls(s);
    return ttyCook(s);
  }

  /**
   * Wrap a raw SSH InputStream and get a cleaned Reader (best for long/interactive).
   * Usage:
   * try (BufferedReader br = new BufferedReader(SshSanitizer.stream(ch.getInvertedOut()))) { ... }
   */
  public static Reader stream(InputStream raw) {
    return stream(raw, StandardCharsets.UTF_8);
  }

  public static Reader stream(InputStream raw, Charset cs) {
    InputStream cleaned = new AnsiStrippingInputStream(raw);
    return new InputStreamReader(cleaned, cs);
  }

  // --- String cleaning helpers ------------------------------------------------

  // CSI: ESC [ ... <final 0x40-0x7E>
  private static final Pattern ANSI_CSI =
      Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

  // OSC: ESC ] ... (ends with BEL or ST=ESC \)
  private static final Pattern ANSI_OSC =
      Pattern.compile("\u001B\\][^\u0007\u001B]*\u0007|\u001B\\][^\u001B]*\u001B\\\\");
  // DCS/PM/APC: ESC P|^|_ ... ST
  private static final Pattern ANSI_DCS_LIKE =
      Pattern.compile("\u001B[P^_][\\s\\S]*?\u001B\\\\");

  private static String stripAnsiAll(String s) {
    s = ANSI_CSI.matcher(s).replaceAll("");
    s = ANSI_OSC.matcher(s).replaceAll("");
    s = ANSI_DCS_LIKE.matcher(s).replaceAll("");
    return s;
  }

  // Drop C0 controls except \n, \r, \t; also drop DEL.
  private static final Pattern CONTROL_CHARS =
      Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

  private static String stripControls(String s) {
    return CONTROL_CHARS.matcher(s).replaceAll("");
  }

  /** Apply TTY semantics: backspace removes prev char; CR resets line. */
  public static String ttyCook(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\b': if (out.length() > 0) out.deleteCharAt(out.length() - 1); break;
        case '\r': out.setLength(0); break; // overwrite line
        default:   out.append(c);
      }
    }
    return out.toString();
  }

  // --- Streaming byte filter --------------------------------------------------

  /**
   * Filter that strips ANSI/VT100 sequences & control chars from a byte stream.
   * Keeps: '\n', '\r', '\t', and ALL bytes >= 0x80 (so UTF-8 survives).
   */
  private static final class AnsiStrippingInputStream extends FilterInputStream {
    private enum State { NORMAL, ESC, CSI, OSC, DCS_LIKE }
    private State state = State.NORMAL;
    private boolean maybeST = false;

    AnsiStrippingInputStream(InputStream in) { super(in); }

    @Override public int read() throws IOException {
      while (true) {
        int b = super.read();
        if (b == -1) return -1;

        switch (state) {
          case NORMAL:
            if (b == 0x1B) { state = State.ESC; continue; }
            if (isAllowed(b)) return b;    // pass-through text + UTF-8 bytes
            continue;                      // drop other controls
          case ESC:
            if (b == '[') { state = State.CSI; continue; }
            if (b == ']') { state = State.OSC; maybeST = false; continue; }
            if (b == 'P' || b == '^' || b == '_') { state = State.DCS_LIKE; maybeST = false; continue; }
            state = State.NORMAL;          // swallow single-ESC final
            continue;
          case CSI:
            if (b >= 0x40 && b <= 0x7E) { state = State.NORMAL; }
            continue;
          case OSC:
            if (b == 0x07) { state = State.NORMAL; maybeST = false; continue; }     // BEL
            if (b == 0x1B) { maybeST = true; continue; }                            // ESC ?
            if (maybeST) { maybeST = false; if (b == '\\') state = State.NORMAL; }  // ESC \
            continue;
          case DCS_LIKE:
            if (b == 0x1B) { maybeST = true; continue; }
            if (maybeST) { maybeST = false; if (b == '\\') state = State.NORMAL; }
            continue;
        }
      }
    }

    @Override public int read(byte[] buf, int off, int len) throws IOException {
      if (buf == null) throw new NullPointerException();
      if (off < 0 || len < 0 || len > buf.length - off) throw new IndexOutOfBoundsException();
      int i = 0;
      while (i < len) {
        int ch = read();
        if (ch == -1) return (i == 0) ? -1 : i;
        buf[off + i++] = (byte) ch;
        if (available() == 0) break; // return quickly with something
      }
      return i;
    }

    private static boolean isAllowed(int b) {
      return b == '\n' || b == '\r' || b == '\t' || (b >= 0x20 && b != 0x7F) || (b & 0xFF) >= 0x80;
    }
  }

  // --- Tiny demos -------------------------------------------------------------

  /** Example: clean an already-captured string. */
  public static void demoString() {
    String raw = "\u001B[32mOK\u001B[0m \rPROG 10%\rPROG 100%\nHello\b!\n";
    System.out.println(clean(raw));
    // => PROG 100%
    // => Hell!
  }

  /** Example: stream usage with a ChannelShell's output */
  public static void demoStream(InputStream sshOut) throws IOException {
    try (BufferedReader br = new BufferedReader(SshSanitizer.stream(sshOut))) {
      String line;
      while ((line = br.readLine()) != null) {
        System.out.println(line);
      }
    }
  }
}