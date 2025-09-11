package com.virima.tes;

import java.util.regex.Pattern;

public final class TerminalSanitizer {
  private TerminalSanitizer(){}

  // Match: CSI  ESC [ ... <final 0x40-0x7E>  (includes DEC private like '?25')
  private static final Pattern CSI =
      Pattern.compile("\u001B\\[[0-9:;<=>?]*[ -/]*[@-~]");

  // OSC: ESC ] ... BEL  OR  ESC ] ... ESC \
  private static final Pattern OSC =
      Pattern.compile("\u001B\\][^\u0007\u001B]*\u0007|\u001B\\][^\u001B]*\u001B\\\\");

  // DCS/PM/APC: ESC P|^|_ ... ST (ESC \)
  private static final Pattern DCS_LIKE =
      Pattern.compile("\u001B[P^_][\\s\\S]*?\u001B\\\\");

  // Drop C0 controls except \n, \r, \t; also drop DEL (0x7F)
  private static final Pattern CONTROLS =
      Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

  /** Remove ANSI/VT sequences (CSI/OSC/DCS/PM/APC) */
  public static String stripAnsiAll(String s) {
    if (s == null || s.isEmpty()) return s;
    s = CSI.matcher(s).replaceAll("");
    s = OSC.matcher(s).replaceAll("");
    s = DCS_LIKE.matcher(s).replaceAll("");
    return s;
  }

  /** Apply TTY semantics: backspace deletes; carriage return overwrites line */
  public static String ttyCook(String s) {
    if (s == null || s.isEmpty()) return s;
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\b': if (out.length() > 0) out.deleteCharAt(out.length() - 1); break;
        case '\r': out.setLength(0); break; // reset to col 0
        default:   out.append(c);
      }
    }
    return out.toString();
  }

  public static String clean(String raw) {
    String s = stripAnsiAll(raw);
    // keep \n, \r, \t; drop other non-printable controls
    s = CONTROLS.matcher(s).replaceAll("");
    // cook overprints after controls removed
    s = ttyCook(s);
    // normalize newlines, trim crazy leading blank screens
    s = s.replace("\r\n", "\n").replace("\r", "\n");
    s = s.replaceAll("\n{3,}", "\n\n").trim();
    return s;
  }
}