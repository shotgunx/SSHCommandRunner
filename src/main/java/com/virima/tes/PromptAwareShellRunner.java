package com.virima.tes;

import com.virima.smartRunner.CommandResult;
import com.virima.utils.SshSanitizer;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Execute a command through ChannelShell:
 *  - Waits for initial prompt
 *  - Sends command
 *  - Reads until next prompt OR quiet time
 *  - Sanitizes terminal escape sequences
 */
public final class PromptAwareShellRunner {
  // Default prompts:
  // Windows: drive/path + '>' (e.g., C:\Users\me>)
  // POSIX: ends with '$ ' or '# ' at line end
  public static final Pattern DEFAULT_PROMPT =
      Pattern.compile("(?m)(?:^.*>[ \\t]*$|^.*[$#][ \\t]*$)");

  // How long to consider “no output” = done
  private static final long DEFAULT_QUIET_MILLIS = 400; // tweak to taste

  private PromptAwareShellRunner() {}

  public static CommandResult run(
      ClientSession session,
      String command,
      Pattern promptPattern,         // pass null to use DEFAULT_PROMPT
      long overallTimeoutSeconds,
      long quietMillis               // no-output window to finish read
  ) throws IOException {

    if (promptPattern == null) promptPattern = DEFAULT_PROMPT;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(overallTimeoutSeconds);

    try (ChannelShell ch = session.createShellChannel()) {
      // Make it behave like a typical terminal (so servers send prompts)
      ch.setPtyType("vt100");
      ch.setUsePty(true);

      ch.open().verify(TimeUnit.SECONDS.toMillis(5));

      InputStream raw = ch.getInvertedOut();
      Reader reader = SshSanitizer.stream(raw, StandardCharsets.UTF_8); // streaming, already strips ANSI
      BufferedReader br = new BufferedReader(reader);

      OutputStream in = ch.getInvertedIn();

      // 1) Wait for initial prompt
      StringBuilder buf = new StringBuilder();
      waitForPromptOrTimeout(ch, br, buf, promptPattern, deadline, quietMillis);

      // 2) Send the command
      writeLine(in, command);

      // 3) Read until next prompt OR quiet time
      String output = readUntilPromptOrQuiet(ch, br, buf, promptPattern, deadline, quietMillis);

      // 4) Exit cleanly
      writeLine(in, "exit");
      ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED),
                 TimeUnit.SECONDS.toMillis(Math.max(2, overallTimeoutSeconds / 10)));

      // Final polish in case any line-overwrite artifacts snuck in
      String cleaned = TerminalSanitizer.clean(output);

      return new CommandResult(cleaned, "", true, "shell",0);
    }
  }

  private static void writeLine(OutputStream in, String s) throws IOException {
    in.write((s + "\r\n").getBytes(StandardCharsets.UTF_8));
    in.flush();
  }

  private static void waitForPromptOrTimeout(
      ChannelShell ch, BufferedReader br, StringBuilder buf,
      Pattern prompt, long deadlineNanos, long quietMillis
  ) throws IOException {
    long lastData = System.nanoTime();
    char[] tmp = new char[2048];

    while (System.nanoTime() < deadlineNanos) {
      // read non-blocking-ish
      if (br.ready()) {
        int n = br.read(tmp);
        if (n == -1) break;
        buf.append(tmp, 0, n);
        lastData = System.nanoTime();
        if (matchesPromptTail(buf, prompt)) {
          return;
        }
      } else {
        // small nap
        sleepTiny();
        if (elapsedMillis(lastData) >= quietMillis && buf.length() > 0) {
          // quiet but no prompt—likely first banner; keep waiting a bit longer
        }
      }
    }
    // If we land here, the server never printed a recognizable prompt.
    // We won’t fail—command may still work, just proceed.
  }

  private static String readUntilPromptOrQuiet(
      ChannelShell ch, BufferedReader br, StringBuilder buf,
      Pattern prompt, long deadlineNanos, long quietMillis
  ) throws IOException {
    StringBuilder out = new StringBuilder(4096);
    long lastData = System.nanoTime();
    char[] tmp = new char[4096];

    while (System.nanoTime() < deadlineNanos) {
      if (br.ready()) {
        int n = br.read(tmp);
        if (n == -1) break;
        buf.append(tmp, 0, n);
        lastData = System.nanoTime();

        // Try to split lines fast to avoid huge buffers
        int cut = buf.lastIndexOf("\n");
        if (cut >= 0) {
          out.append(buf, 0, cut + 1);
          buf.delete(0, cut + 1);
        }

        if (matchesPromptTail(buf, prompt)) {
          // Append whatever came before the prompt line, but not the prompt itself
          // Grab everything except the last line (prompt)
          int lastNL = out.lastIndexOf("\n");
          if (lastNL >= 0) {
            // out already has lines; keep them
          }
          // Remove prompt line fragment from buf
          // We'll just drop buf completely because we hit prompt at tail
          // (If you want to preserve the prompt line, comment this out)
          buf.setLength(0);
          break;
        }
      } else {
        sleepTiny();
        if (elapsedMillis(lastData) >= quietMillis) {
          // consider we're done (command produced no more output)
          break;
        }
      }
    }

    // Flush remainder of buffer (non-prompt tail)
    if (buf.length() > 0) {
      out.append(buf);
      buf.setLength(0);
    }

    return out.toString();
  }

  private static boolean matchesPromptTail(CharSequence s, Pattern prompt) {
    // Only scan the last few KB to keep it cheap
    int len = s.length();
    int from = Math.max(0, len - 8192);
    CharSequence tail = s.subSequence(from, len);
    Matcher m = prompt.matcher(tail);
    // Ensure it hits near the end (line-oriented prompt)
    return m.find() && m.end() >= tail.length() - 2;
  }

  private static void sleepTiny() {
    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
  }

  private static long elapsedMillis(long sinceNano) {
    return Duration.ofNanos(System.nanoTime() - sinceNano).toMillis();
  }



    public static void main(String[] args) {

    }
}