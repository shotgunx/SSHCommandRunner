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
    private static final boolean DEBUG = false;

    // Terminal dimensions - like setting your monitor resolution
    private static final int TERMINAL_WIDTH = 200;
    private static final int TERMINAL_HEIGHT = 24;

    // Patterns for detecting prompts and pagination
    private static final Pattern PROMPT_PATTERN = Pattern.compile("[\\w\\-\\.@]+[#>$%]\\s*$");
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

        try {
            // === Step 1: Set up the SSH channel ===
            channel = (ChannelShell) session.openChannel("shell");

            if (isAdmin) {
                channel.setPtyType("vt100", TERMINAL_WIDTH, TERMINAL_HEIGHT, 640, 480);
            } else {
                channel.setPtyType("dumb", TERMINAL_WIDTH, TERMINAL_HEIGHT, 640, 480);
            }
            channel.setPty(true);

            // === Step 2: Get I/O streams BEFORE connect() ===
            // IMPORTANT: JSch requires getInputStream() to be called before connect()
            InputStream inputStream = channel.getInputStream();
            OutputStream outputStream = channel.getOutputStream();

            // === Step 3: Now connect ===
            channel.connect(15000);

            // === Step 4: Create JediTerm components ===
            // This is like setting up your virtual monitor's internals
            StyleState styleState = new StyleState();
            TerminalTextBuffer textBuffer = new TerminalTextBuffer(TERMINAL_WIDTH, TERMINAL_HEIGHT, styleState);
            BackBufferDisplay display = new BackBufferDisplay(textBuffer);
            JediTerminal terminal = new JediTerminal(display, textBuffer, styleState);

            // === Step 5: Set up JediTerm processor ===
            // Create the terminal processor that runs in background
            JediTermProcessor processor = new JediTermProcessor(inputStream, terminal, textBuffer);

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                executor.submit(processor);

                // === Step 5: Handle login sequence ===
                handleLogin(processor, outputStream, session.getUserName(), password);

                // === Step 6: Detect the prompt ===
                String detectedPrompt = detectPrompt(processor, outputStream);
                debug("Detected prompt: '" + detectedPrompt + "'");

                // === Step 7: Execute the command ===
                // Clear the buffer before sending command so we only capture command output
                processor.clearRawBuffer();
                
                sendLine(outputStream, command);

                // Wait for command completion, handling "more" prompts
                outputString = waitForCommandCompletion(processor, outputStream, detectedPrompt);

                // Clean up the output
                outputString = cleanOutput(outputString, command, detectedPrompt);

                if (DEBUG) {
                    System.out.println("=== Command Output ===");
                    System.out.println(outputString);
                    System.out.println("======================");
                }

                // Send exit command
                sendLine(outputStream, "exit");
                Thread.sleep(500);

                exitCode = channel.getExitStatus();

                // Shutdown the processor
                processor.stop();
            }

        } catch (Exception e) {
            e.printStackTrace();
            outputString = "Error: " + e.getMessage();
            return new CommandResult(false, exitCode, outputString);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }

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
     * Strategy: Use the raw buffer which accumulates ALL data received.
     * Wait until output stabilizes (no new data) AND we see the prompt again.
     */
    private String waitForCommandCompletion(JediTermProcessor processor, OutputStream output, String expectedPrompt) throws IOException {
        String escapedPrompt = Pattern.quote(expectedPrompt);
        // Match prompt at end of content (with possible trailing whitespace/newlines)
        Pattern promptPattern = Pattern.compile(escapedPrompt + "\\s*$");

        long timeout = 60000; // 60 second total timeout
        long stabilityTimeout = 3000; // Consider stable if no new data for 3 seconds
        long startTime = System.currentTimeMillis();
        long lastChangeTime = System.currentTimeMillis();
        
        String lastRawContent = "";
        int lastRawLength = 0;

        debug("Waiting for command completion, looking for prompt: '" + expectedPrompt + "'");

        while (System.currentTimeMillis() - startTime < timeout) {
            String rawContent = processor.getRawContent();
            String screenContent = processor.getScreenContent();
            
            // Check if we received new data
            if (rawContent.length() != lastRawLength) {
                lastChangeTime = System.currentTimeMillis();
                lastRawLength = rawContent.length();
                lastRawContent = rawContent;
                
                debug("Data received, total raw length: " + lastRawLength);
            }

            // Check for "more" prompt - need to send space to continue
            if (MORE_PATTERN.matcher(screenContent).find() || MORE_PATTERN.matcher(rawContent).find()) {
                debug("Found 'more' prompt, sending space");
                output.write(' ');
                output.flush();
                sleep(300);
                continue;
            }

            // Check if output has stabilized AND we see the prompt
            long timeSinceLastChange = System.currentTimeMillis() - lastChangeTime;
            
            // Look for the prompt in the last part of the content
            String lastPart = rawContent.length() > 500 ? rawContent.substring(rawContent.length() - 500) : rawContent;
            boolean hasPrompt = promptPattern.matcher(lastPart).find() || lastPart.contains(expectedPrompt);
            
            if (hasPrompt && timeSinceLastChange > 1000) {
                debug("Found prompt and output stabilized, command complete");
                break;
            }

            // If output has been stable for a while, assume command is done
            if (timeSinceLastChange > stabilityTimeout && lastRawLength > 0) {
                debug("Output stabilized for " + stabilityTimeout + "ms, assuming complete");
                break;
            }

            sleep(200);
        }

        // Return the accumulated raw content
        String result = processor.getRawContent();
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
    private String cleanOutput(String output, String command, String prompt) {
        String[] lines = output.split("\\r?\\n");
        StringBuilder cleaned = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            // Skip empty lines at start
            if (cleaned.length() == 0 && trimmed.isEmpty()) {
                continue;
            }

            // Skip the echoed command
            if (trimmed.equals(command) || trimmed.endsWith(command)) {
                continue;
            }

            // Skip lines that are just the prompt
            if (trimmed.equals(prompt)) {
                continue;
            }

            // Remove trailing whitespace padding from JediTerm
            String cleanedLine = line.replaceAll("\\s+$", "");
            if (!cleanedLine.isEmpty()) {
                cleaned.append(cleanedLine).append("\n");
            }
        }

        return cleaned.toString().trim();
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