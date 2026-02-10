package com.virima.jsch;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.emulator.Emulator;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSH Command Executor using JediTerm for terminal emulation.
 * <p>
 * Think of JediTerm like a "virtual monitor" - when your TV receives a signal
 * full of encoding information, it decodes it and displays a clean picture.
 * JediTerm does the same for terminal data: it receives raw bytes with escape
 * codes and maintains a clean, readable screen buffer.
 */
public class JediTermSshExecutor {

    // Set to true to enable debug output
    private static final boolean DEBUG = true;

    // Terminal dimensions - like setting your monitor resolution
    private static final int TERMINAL_WIDTH = 200;
    private static final int TERMINAL_HEIGHT = 24;

    // Patterns for detecting prompts and pagination
    // Matches various device prompts:
    // - Cisco user mode: Switch>, Router>
    // - Cisco privileged: Switch#, Router#
    // - Cisco config: Switch(config)#, Switch(config-if)#
    // - Linux: user@host$, root@host#
    // - With stack/port: Switch:1>, Switch:2#
    // - Bare prompts: >, #, $
    private static final Pattern PROMPT_PATTERN = Pattern.compile("[\\w\\-\\.@:]*(?:\\([\\w\\-]+\\))?[#>$%]\\s*$");
    private static final Pattern MORE_PATTERN = Pattern.compile("(?i)(--\\s*more\\s*--|<---\\s*more\\s*--->|More:.*<space>.*Quit:.*q)");
    private static final Pattern LOGIN_PROMPT = Pattern.compile("(?i)(user\\s*name|username|login|user)\\s*:\\s*$");
    private static final Pattern PASSWORD_PROMPT = Pattern.compile("(?i)password\\s*:\\s*$");
    private static final Pattern AUTH_FAILURE = Pattern.compile("(?i)(authentication failed|access denied|login incorrect)");

    /**
     * Executes a command over SSH using JediTerm for terminal emulation.
     */
    public CommandResult executeCommand(Session session, String password, String command, boolean isAdmin) {
        ChannelShell channel = null;
        String outputString = "";
        int exitCode = -1;
        String host = session.getHost();

        debug("═══════════════════════════════════════════════════════════════");
        debug("STARTING executeCommand for host: " + host);
        debug("Command: '" + command + "'");
        debug("isAdmin: " + isAdmin);
        debug("═══════════════════════════════════════════════════════════════");

        try {
            // === Step 1: Set up the SSH channel ===
            debug("[STEP 1] Opening shell channel...");
            channel = (ChannelShell) session.openChannel("shell");

            if (isAdmin) {
                channel.setPtyType("vt100", TERMINAL_WIDTH, TERMINAL_HEIGHT, 640, 480);
                debug("[STEP 1] PTY type: vt100 (admin mode)");
            } else {
                channel.setPtyType("dumb", TERMINAL_WIDTH, TERMINAL_HEIGHT, 640, 480);
                debug("[STEP 1] PTY type: dumb (non-admin mode)");
            }
            channel.setPty(true);

            // === Step 2: Get I/O streams BEFORE connect() ===
            debug("[STEP 2] Getting I/O streams...");
            InputStream inputStream = channel.getInputStream();
            OutputStream outputStream = channel.getOutputStream();

            // === Step 3: Now connect ===
            debug("[STEP 3] Connecting channel (timeout: 15s)...");
            channel.connect(15000);
            debug("[STEP 3] Channel connected successfully");

            // === Step 4: Create JediTerm components ===
            debug("[STEP 4] Creating JediTerm components...");
            StyleState styleState = new StyleState();
            TerminalTextBuffer textBuffer = new TerminalTextBuffer(TERMINAL_WIDTH, TERMINAL_HEIGHT, styleState);
            BackBufferDisplay display = new BackBufferDisplay(textBuffer);
            JediTerminal terminal = new JediTerminal(display, textBuffer, styleState);

            // === Step 5: Set up JediTerm processor ===
            debug("[STEP 5] Starting JediTerm processor...");
            JediTermProcessor processor = new JediTermProcessor(inputStream, terminal, textBuffer);

            try(ExecutorService executor = Executors.newSingleThreadExecutor()){
                executor.submit(processor);
                debug("[STEP 5] Processor started");


                // === Step 6: Handle login sequence ===
                debug("[STEP 6] Handling login sequence...");
                handleLogin(processor, outputStream, session.getUserName(), password);
                debug("[STEP 6] Login complete");

                // === Step 7: Detect the prompt ===
                debug("[STEP 7] Detecting prompt...");
                String detectedPrompt = detectPrompt(processor, outputStream);
                debug("[STEP 7] Detected prompt: '" + detectedPrompt + "' (length=" + detectedPrompt.length() + ")");

                // === Step 8: Execute the command ===
                debug("[STEP 8] Preparing to execute command...");
                debug("[STEP 8] Clearing raw buffer...");
                processor.clearRawBuffer();
                processor.cleartextBuffer();

                debug("[STEP 8] Waiting 300ms for buffer stabilization...");
                sleep(300);

                debug("[STEP 8] Sending command: '" + command + "'");
                sendLine(outputStream, command);

                debug("[STEP 8] Waiting 500ms for device to process...");
                sleep(700);

                // === Step 9: Wait for command completion ===
                debug("[STEP 9] Waiting for command completion...");
                outputString = waitForCommandCompletion(processor, outputStream, detectedPrompt);
                debug("[STEP 9] Raw output received, length: " + outputString.length());

                // === Step 10: Clean up the output ===
                debug("[STEP 10] Cleaning output...");
                debug("[STEP 10] RAW OUTPUT BEFORE CLEAN:");
                debug("─────────────────────────────────────");
                debug(outputString);
                debug("─────────────────────────────────────");

                outputString = cleanOutput(outputString, command, detectedPrompt);

                debug("[STEP 10] CLEANED OUTPUT:");
                debug("─────────────────────────────────────");
                debug(outputString);
                debug("─────────────────────────────────────");
                debug("[STEP 10] Cleaned output length: " + outputString.length());

                if (DEBUG) {
                    System.out.println("=== Command Output ===");
                    System.out.println(outputString);
                    System.out.println("======================");
                }

                // === Step 11: Send exit command ===
                debug("[STEP 11] Sending exit command...");
                sendLine(outputStream, "exit");
                Thread.sleep(500);

                exitCode = channel.getExitStatus();
                debug("[STEP 11] Exit code: " + exitCode);

            } finally {
                debug("Cleaning up: stopping processor and executor...");
                processor.stop();
            }

        } catch (Exception e) {
            debug("ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            return new CommandResult(false, exitCode, outputString);
        } finally {
            if (channel != null) {
                debug("Disconnecting channel...");
                channel.disconnect();
            }
        }

        debug("═══════════════════════════════════════════════════════════════");
        debug("COMPLETED executeCommand - Output length: " + outputString.length());
        debug("═══════════════════════════════════════════════════════════════");

        return new CommandResult(true, exitCode, outputString);
    }

    // ==================== LOGIN HANDLING ====================

    private void handleLogin(JediTermProcessor processor, OutputStream output, String username, String password) throws IOException {
        long timeout = 10000;
        long startTime = System.currentTimeMillis();

        // Wait for initial data to arrive and be processed
        sleep(1000);

        while (System.currentTimeMillis() - startTime < timeout) {
            String screenContent = processor.getScreenContent();
            String rawContent = processor.getRawContent();

            // Debug: print what we're seeing
            debug("Screen content: '" + screenContent.replace("\n", "\\n") + "'");
            debug("Raw content: '" + rawContent.replace("\n", "\\n").replace("\r", "\\r") + "'");

            // Check both screen and raw content for prompts
            String contentToCheck = screenContent + " " + rawContent;

            // Check for auth failure
            if (AUTH_FAILURE.matcher(contentToCheck).find()) {
                throw new IOException("Authentication failed");
            }

            // Check for device prompt (already logged in)
            if (PROMPT_PATTERN.matcher(contentToCheck).find()) {
                debug("Found device prompt, login complete");
                return;
            }

            // Check for username prompt
            if (LOGIN_PROMPT.matcher(contentToCheck).find()) {
                debug("Found login prompt, sending username: " + username);
                sendLine(output, username);
                sleep(1000);
                continue;
            }

            // Check for password prompt
            if (PASSWORD_PROMPT.matcher(contentToCheck).find()) {
                debug("Found password prompt, sending password");
                sendLine(output, password);
                sleep(1000);
                continue;
            }

            sleep(200);
        }
        debug("[DEBUG] Login handling timed out");
    }

    // ==================== PROMPT DETECTION ====================

    /**
     * Detects the shell prompt - like tapping a sleeping screen to see what appears.
     */
    private String detectPrompt(JediTermProcessor processor, OutputStream output) throws IOException {
        // Wait for any initial output to settle
        sleep(1000);

        // Send empty line to get a fresh prompt
        sendLine(output, "");
        sleep(1000);

        // Get the current screen content and find the prompt
        String screenContent = processor.getScreenContent();
        String[] lines = screenContent.split("\\r?\\n");

        // Search from bottom up for a line matching prompt pattern
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && PROMPT_PATTERN.matcher(line).find()) {
                return line;
            }
        }

        throw new IOException("Could not detect device prompt");
    }

    // ==================== COMMAND EXECUTION ====================

    /**
     * Waits for command to complete, handling pagination ("more" prompts).
     *
     * Strategy: Weighted Signals approach.
     *
     * Combines multiple signals with different weights to determine completion.
     * This is more robust than relying on a single signal because:
     * - Multiple signals can compensate for each other
     * - Handles edge cases where one signal might fail
     * - Adapts to different device behaviors
     *
     * Signals and weights:
     * - Time elapsed > minimum       : +1 point
     * - Has content (rawLength > 0)  : +1 point
     * - Raw data stable > 1.5s       : +2 points
     * - Screen stable (3+ checks)    : +2 points
     * - Raw data stable > 3s         : +1 point (bonus for long stability)
     *
     * Exit when score >= 5 (out of max 7)
     */
    private String waitForCommandCompletion(JediTermProcessor processor, OutputStream output, String expectedPrompt) throws IOException {
        // Timing configuration
        long timeout = 60000;            // 60 second total timeout
        long minimumWaitTime = 1500;     // Minimum time before considering complete
        long rawStableThreshold = 1500;  // Raw data stable threshold for +2 points
        long rawStableBonusThreshold = 3000; // Bonus point if stable this long
        long checkInterval = 400;        // Check every 400ms

        // Score configuration
        int requiredScore = 5;           // Need this score to exit
        int screenStableChecksNeeded = 3; // Screen stable checks for +2 points

        long startTime = System.currentTimeMillis();
        long lastDataChangeTime = System.currentTimeMillis();

        String lastScreenContent = "";
        int stableScreenCount = 0;
        int lastRawLength = 0;

        debug("Waiting for command completion (weighted-signals), requiredScore=" + requiredScore);

        while (System.currentTimeMillis() - startTime < timeout) {
            String rawContent = processor.getRawContent();
            String screenContent = processor.getScreenContent();
            long elapsed = System.currentTimeMillis() - startTime;
            long timeSinceLastData = System.currentTimeMillis() - lastDataChangeTime;

            // Track raw data changes
            if (rawContent.length() != lastRawLength) {
                lastDataChangeTime = System.currentTimeMillis();
                timeSinceLastData = 0;
                lastRawLength = rawContent.length();
                debug("Raw data received, length: " + lastRawLength);
            }

            // Check for "more" prompt - need to send space to continue pagination
            // IMPORTANT: Only check screenContent, NOT rawContent!
            // rawContent accumulates ALL data and never forgets old "-- MORE --" prompts,
            // which would cause infinite matching. screenContent shows current display only.
            Matcher screenMoreMatcher = MORE_PATTERN.matcher(screenContent);
            if (screenMoreMatcher.find()) {
                debug("MORE matched in SCREEN content: '" + screenMoreMatcher.group() + "'");
                // Show context around the match
                int start = Math.max(0, screenMoreMatcher.start() - 30);
                int end = Math.min(screenContent.length(), screenMoreMatcher.end() + 30);
                debug("MORE context in screen: '..." + screenContent.substring(start, end).replace("\n", "\\n").replace("\r", "\\r") + "...'");
                debug("Found 'more' prompt, sending space");
                output.write(' ');
                output.flush();
                stableScreenCount = 0;
                sleep(500);  // Wait longer to let the screen update
                continue;
            }

            // Track screen stability
            if (screenContent.equals(lastScreenContent)) {
                stableScreenCount++;
            } else {
                stableScreenCount = 0;
                lastScreenContent = screenContent;
            }

            // Calculate weighted score
            int score = 0;
            StringBuilder scoreDebug = new StringBuilder();

            // Signal 1: Minimum time elapsed (+1)
            if (elapsed >= minimumWaitTime) {
                score += 1;
                scoreDebug.append("time(+1) ");
            }

            // Signal 2: Has content (+1)
            if (lastRawLength > 0) {
                score += 1;
                scoreDebug.append("content(+1) ");
            }

            // Signal 3: Raw data stable > threshold (+2)
            if (timeSinceLastData >= rawStableThreshold) {
                score += 2;
                scoreDebug.append("rawStable(+2) ");
            }

            // Signal 4: Screen stable for N checks (+2)
            if (stableScreenCount >= screenStableChecksNeeded) {
                score += 2;
                scoreDebug.append("screenStable(+2) ");
            }

            // Signal 5: Raw data stable for longer period - bonus (+1)
            if (timeSinceLastData >= rawStableBonusThreshold) {
                score += 1;
                scoreDebug.append("rawStableBonus(+1) ");
            }

            debug("Score: " + score + "/" + requiredScore + " [" + scoreDebug.toString().trim() + "] " +
                    "elapsed=" + elapsed + "ms, rawStable=" + timeSinceLastData + "ms, screenChecks=" + stableScreenCount);

            // Check if we have enough confidence to exit
            if (score >= requiredScore) {
                debug("Command complete: score=" + score + " >= " + requiredScore);
                break;
            }

            sleep(checkInterval);
        }

        // Return the accumulated raw content
        String result = processor.getScreenContent();
        debug("Final raw content length: " + result.length());
        return result;
    }

    // ==================== UTILITY METHODS ====================

    private void sendLine(OutputStream output, String text) throws IOException {
        output.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void debug(String message) {
        if (DEBUG) {
            System.out.println("[DEBUG] " + message);
        }
    }

    /**
     * Cleans the output by removing the echoed command and trailing prompt.
     */
    private  String cleanOutput(String output, String command, String prompt) {
        debug("[cleanOutput] Starting cleanup...");
        debug("[cleanOutput] Input length: " + output.length());
        debug("[cleanOutput] Command to remove: '" + command + "'");
        debug("[cleanOutput] Prompt to remove: '" + prompt + "'");

        String[] lines = output.split("\\r?\\n");
        debug("[cleanOutput] Total lines: " + lines.length);

        StringBuilder cleaned = new StringBuilder();
        boolean foundCommandLine = false;
        int skippedCount = 0;
        int addedCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Skip empty lines at start
            if (cleaned.isEmpty() && trimmed.isEmpty()) {
                debug("[cleanOutput] Line " + i + ": SKIP (empty at start)");
                skippedCount++;
                continue;
            }

            // Skip the echoed command (first occurrence only)
            if (!foundCommandLine && (trimmed.equals(command) || trimmed.endsWith(command))) {
                debug("[cleanOutput] Line " + i + ": SKIP (command echo): '" + trimmed + "'");
                foundCommandLine = true;
                skippedCount++;
                continue;
            }

            // Skip lines that are ONLY the prompt (not lines containing prompt + content)
            if (trimmed.equals(prompt)) {
                debug("[cleanOutput] Line " + i + ": SKIP (prompt only): '" + trimmed + "'");
                skippedCount++;
                continue;
            }

            // Skip lines that are prompt + command (like "> show version")
            if (trimmed.startsWith(prompt) && trimmed.substring(prompt.length()).trim().equals(command)) {
                debug("[cleanOutput] Line " + i + ": SKIP (prompt+command): '" + trimmed + "'");
                skippedCount++;
                continue;
            }

            // Remove trailing whitespace padding from JediTerm
            String cleanedLine = line.replaceAll("\\s+$", "");
            if (!cleanedLine.isEmpty()) {
                debug("[cleanOutput] Line " + i + ": ADD: '" + (cleanedLine.length() > 80 ? cleanedLine.substring(0, 80) + "..." : cleanedLine) + "'");
                cleaned.append(cleanedLine).append("\n");
                addedCount++;
            } else {
                debug("[cleanOutput] Line " + i + ": SKIP (empty after trim)");
                skippedCount++;
            }
        }

        String result = cleaned.toString().trim();
        debug("[cleanOutput] Cleanup complete - Added: " + addedCount + ", Skipped: " + skippedCount);
        debug("[cleanOutput] Final output length: " + result.length());

        return result;
    }

    // ==================== JEDITERM PROCESSOR ====================

    /**
     * Processes SSH input through JediTerm emulator in a background thread.
     * <p>
     * This is like having a dedicated decoder chip that continuously processes
     * the incoming signal and updates the display buffer.
     */
    private static class JediTermProcessor implements Runnable {
        private final InputStream inputStream;
        private final TerminalTextBuffer textBuffer;
        private final JediTerminal terminal;
        private final Object lock = new Object();
        private final StringBuilder rawBuffer = new StringBuilder();
        private volatile boolean running = true;

        public JediTermProcessor(InputStream inputStream, JediTerminal terminal, TerminalTextBuffer textBuffer) {
            this.inputStream = inputStream;
            this.terminal = terminal;
            this.textBuffer = textBuffer;
        }

        @Override
        public void run() {
            try {
                byte[] buffer = new byte[4096];
                int bytesRead;

                while (running && (bytesRead = inputStream.read(buffer)) != -1) {
                    if (bytesRead > 0) {
                        String data = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                        processData(data);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("JediTerm processor error: " + e.getMessage());
                }
            }
        }

        /**
         * Processes a chunk of terminal data through JediTerm.
         */
        private void processData(String data) {
            synchronized (lock) {
                try {
                    // Convert to char array for JediTerm's ArrayTerminalDataStream
                    char[] chars = data.toCharArray();
                    ArrayTerminalDataStream dataStream = new ArrayTerminalDataStream(chars);

                    // Create emulator and process all characters
                    Emulator emulator = new JediEmulator(dataStream, terminal);
                    while (emulator.hasNext()) {
                        emulator.next();
                    }

                    // Also keep raw data for reference
                    rawBuffer.append(data);
                } catch (IOException e) {
                    System.err.println("Error processing terminal data: " + e.getMessage());
                }
            }
        }

        public void stop() {
            running = false;
        }

        /**
         * Gets the current "screen" content - what would be visible on a terminal.
         * JediTerm has already processed all escape sequences, so this is clean text.
         */
        public String getScreenContent() {
            synchronized (lock) {
                return textBuffer.getScreenLines();
            }
        }

        /**
         * Gets the raw content received (before JediTerm processing).
         * Useful for debugging or when JediTerm hasn't processed the data yet.
         */
        public String getRawContent() {
            synchronized (lock) {
                return rawBuffer.toString();
            }
        }

        /**
         * Clears the raw buffer. Call this before sending a command
         * to ensure only command output is captured.
         */
        public void clearRawBuffer() {
            synchronized (lock) {
                rawBuffer.setLength(0);
            }
        }

        /**
         * Clears the raw buffer. Call this before sending a command
         * to ensure only command output is captured.
         */
        public void cleartextBuffer() {
            synchronized (lock) {
                textBuffer.clearScreenBuffer();
            }
        }

        /**
         * Gets the full history + screen content.
         */
        public String getFullContent() {
            synchronized (lock) {
                StringBuilder sb = new StringBuilder();

                // Get history buffer content using new API (getHistoryLinesStorage)
                var historyStorage = textBuffer.getHistoryLinesStorage();
                for (var line : historyStorage) {
                    String lineText = line.getText();
                    if (lineText != null && !lineText.isEmpty()) {
                        sb.append(lineText).append("\n");
                    }
                }

                // Get current screen content
                sb.append(textBuffer.getScreenLines());

                return sb.toString();
            }
        }
    }

    // ==================== BACKBUFFER DISPLAY ====================

    /**
     * Minimal TerminalDisplay implementation for headless operation.
     * Like a monitor that's just a frame buffer without actual display hardware.
     */
    private static class BackBufferDisplay implements com.jediterm.terminal.TerminalDisplay {
        private final TerminalTextBuffer textBuffer;
        private String windowTitle = "";

        public BackBufferDisplay(TerminalTextBuffer textBuffer) {
            this.textBuffer = textBuffer;
        }

        @Override
        public void setCursor(int x, int y) {
        }

        @Override
        public void setCursorShape(com.jediterm.terminal.CursorShape shape) {
        }

        @Override
        public void beep() {
        }

        @Override
        public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
        }

        @Override
        public void setCursorVisible(boolean visible) {
        }

        @Override
        public void useAlternateScreenBuffer(boolean enabled) {
        }

        @Override
        public String getWindowTitle() {
            return windowTitle;
        }

        @Override
        public void setWindowTitle(String name) {
            this.windowTitle = name;
        }

        @Override
        public void terminalMouseModeSet(com.jediterm.terminal.emulator.mouse.MouseMode mode) {
        }

        @Override
        public void setMouseFormat(com.jediterm.terminal.emulator.mouse.MouseFormat format) {
        }

        @Override
        public com.jediterm.terminal.model.TerminalSelection getSelection() {
            return null;
        }

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }
    }

}