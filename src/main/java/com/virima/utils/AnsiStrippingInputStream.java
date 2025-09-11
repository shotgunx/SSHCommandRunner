package com.virima.utils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Strips ANSI/VT100 escape sequences and most control chars from a byte stream.
 * Keeps '\n' and '\r' (so your line breaks survive). Everything else non-printable is dropped.
 *
 * Handles:
 *  - ESC [ ... <final 0x40-0x7E>   (CSI)
 *  - ESC ] ... BEL or ST            (OSC; ends with BEL(0x07) or ESC \)
 *  - ESC P / ESC ^ / ESC _ ... ST   (DCS/PM/APC; end by ST)
 *  - Single ESC + final (e.g., ESCc)
 */
public final class AnsiStrippingInputStream extends FilterInputStream {
  private enum State { NORMAL, ESC, CSI, OSC, DCS_LIKE }
  private State state = State.NORMAL;
  private boolean oscMayEndWithEsc = false; // track ESC preceding '\' for ST in OSC/DCS/PM/APC

  public AnsiStrippingInputStream(InputStream in) {
    super(in);
  }

  @Override
  public int read() throws IOException {
    while (true) {
      int b = super.read();
      if (b == -1) return -1;

      switch (state) {
        case NORMAL:
          if (b == 0x1B) { // ESC
            state = State.ESC;
            continue; // swallow
          }
          if (isPrintableOrNewline(b)) {
            return b;
          }
          // drop other control chars (tabs optional: keep if you want)
          if (b == '\t') return b;
          continue;

        case ESC:
          // ESC [  -> CSI
          if (b == '[') {
            state = State.CSI;
            continue;
          }
          // ESC ]  -> OSC
          if (b == ']') {
            state = State.OSC;
            oscMayEndWithEsc = false;
            continue;
          }
          // ESC P (DCS), ESC ^ (PM), ESC _ (APC) -> DCS-like
          if (b == 'P' || b == '^' || b == '_') {
            state = State.DCS_LIKE;
            oscMayEndWithEsc = false;
            continue;
          }
          // ESC \ -> ST (string terminator) but if we saw raw ESC we just discard both
          // Any other "single" escape final (0x40-0x7E): swallow this one and go NORMAL
          state = State.NORMAL;
          continue;

        case CSI:
          // Consume until a final byte 0x40..0x7E
          if (b >= 0x40 && b <= 0x7E) {
            state = State.NORMAL;
          }
          continue;

        case OSC:
          // OSC terminates with BEL (0x07) OR ST sequence ESC \
          if (b == 0x07) { // BEL
            state = State.NORMAL;
            oscMayEndWithEsc = false;
            continue;
          }
          if (b == 0x1B) { // ESC: could be ST if next is '\'
            oscMayEndWithEsc = true;
            continue;
          }
          if (oscMayEndWithEsc) {
            // Only ESC \ ends OSC; else keep swallowing
            oscMayEndWithEsc = false;
            // If it's '\' (0x5C), end string; else continue in OSC
            if (b == '\\') {
              state = State.NORMAL;
            }
            continue;
          }
          continue;

        case DCS_LIKE:
          // DCS/PM/APC also end with ST (ESC \)
          if (b == 0x1B) {
            oscMayEndWithEsc = true;
            continue;
          }
          if (oscMayEndWithEsc) {
            oscMayEndWithEsc = false;
            if (b == '\\') {
              state = State.NORMAL;
            }
            continue;
          }
          // otherwise swallow
          continue;
      }
    }
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (b == null) throw new NullPointerException();
    if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
    // Fill with cleaned bytes one at a time; avoids returning 0 unless EOF
    int i = 0;
    while (i < len) {
      int ch = read();
      if (ch == -1) {
        return (i == 0) ? -1 : i;
      }
      b[off + i] = (byte) ch;
      i++;
      // Return fast once we got something
      if (available() == 0) break;
    }
    return i;
  }

  private static boolean isPrintableOrNewline(int b) {
    return b == '\n' || b == '\r' || (b >= 0x20 && b <= 0x7E); // basic printable ASCII
    // If you expect UTF-8, you can also keep bytes >= 0x80:
    // return b == '\n' || b == '\r' || (b >= 0x20 && b != 0x7F);
  }
}