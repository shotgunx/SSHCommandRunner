package com.virima.utils;

import com.virima.smartRunner.UserCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles interactive prompts in SSH sessions, including username and password prompts
 */
public class InteractivePromptHandler {
    private static final Logger logger = LoggerFactory.getLogger(InteractivePromptHandler.class);
    
    // Common patterns for username prompts
    private static final Pattern[] USERNAME_PATTERNS = {
        Pattern.compile(".*[Uu]sername\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Ll]ogin\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Uu]ser\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Ll]ogin\\s+[Nn]ame\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Uu]ser\\s+[Nn]ame\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Aa]ccount\\s*:?\\s*$", Pattern.CASE_INSENSITIVE)
    };
    
    // Common patterns for password prompts
    private static final Pattern[] PASSWORD_PATTERNS = {
        Pattern.compile(".*[Pp]assword\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Pp]assword\\s+for\\s+.*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Pp]assphrase\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Ee]nter\\s+[Pp]assword\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\[sudo\\]\\s+[Pp]assword\\s+for\\s+.*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*'s\\s+[Pp]assword\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Pp]lease\\s+[Ee]nter\\s+[Pp]assword\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Cc]urrent\\s+[Pp]assword\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Nn]ew\\s+[Pp]assword\\s*:?\\s*$", Pattern.CASE_INSENSITIVE)
    };
    
    // Common patterns for yes/no prompts
    private static final Pattern[] YES_NO_PATTERNS = {
        Pattern.compile(".*\\(yes/no\\)\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\(y/n\\)\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Cc]ontinue\\s*\\?\\s*\\(yes/no\\)\\s*:?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Aa]re\\s+you\\s+sure.*\\?\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[Cc]onfirm\\s*.*\\?\\s*$", Pattern.CASE_INSENSITIVE)
    };
    
    // Common patterns for standard shell prompts
    private static final Pattern[] SHELL_PROMPT_PATTERNS = {
        Pattern.compile(".*[$#>]\\s*$"),
        Pattern.compile(".*][$#>]\\s*$"),
        Pattern.compile(".*@.*:.*[$#>]\\s*$"),
        Pattern.compile(".*\\[[^\\]]+\\][$#>]\\s*$")
    };
    
    public enum PromptType {
        USERNAME,
        PASSWORD,
        YES_NO,
        SHELL_PROMPT,
        UNKNOWN
    }
    
    public static class PromptDetectionResult {
        private final PromptType type;
        private final String prompt;
        private final boolean requiresResponse;
        
        public PromptDetectionResult(PromptType type, String prompt, boolean requiresResponse) {
            this.type = type;
            this.prompt = prompt;
            this.requiresResponse = requiresResponse;
        }
        
        public PromptType getType() {
            return type;
        }
        
        public String getPrompt() {
            return prompt;
        }
        
        public boolean requiresResponse() {
            return requiresResponse;
        }
        
        @Override
        public String toString() {
            return "PromptDetectionResult{" +
                    "type=" + type +
                    ", prompt='" + prompt + '\'' +
                    ", requiresResponse=" + requiresResponse +
                    '}';
        }
    }
    
    /**
     * Detects the type of prompt from the output
     */
    public static PromptDetectionResult detectPrompt(String output) {
        if (output == null || output.isEmpty()) {
            return new PromptDetectionResult(PromptType.UNKNOWN, "", false);
        }
        
        // Get the last non-empty line
        String[] lines = output.split("\\r?\\n");
        String lastLine = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                lastLine = line;
                break;
            }
        }
        
        logger.debug("[PROMPT-DETECT] Checking last line: '{}'", lastLine);
        
        // Check for username prompt
        for (Pattern pattern : USERNAME_PATTERNS) {
            if (pattern.matcher(lastLine).matches()) {
                logger.info("[PROMPT-DETECT] Detected USERNAME prompt: '{}'", lastLine);
                return new PromptDetectionResult(PromptType.USERNAME, lastLine, true);
            }
        }
        
        // Check for password prompt
        for (Pattern pattern : PASSWORD_PATTERNS) {
            if (pattern.matcher(lastLine).matches()) {
                logger.info("[PROMPT-DETECT] Detected PASSWORD prompt: '{}'", lastLine);
                return new PromptDetectionResult(PromptType.PASSWORD, lastLine, true);
            }
        }
        
        // Check for yes/no prompt
        for (Pattern pattern : YES_NO_PATTERNS) {
            if (pattern.matcher(lastLine).matches()) {
                logger.info("[PROMPT-DETECT] Detected YES/NO prompt: '{}'", lastLine);
                return new PromptDetectionResult(PromptType.YES_NO, lastLine, true);
            }
        }
        

        
        logger.debug("[PROMPT-DETECT] No specific prompt pattern matched for: '{}'", lastLine);
        return new PromptDetectionResult(PromptType.UNKNOWN, lastLine, false);
    }
    
    /**
     * Handles interactive prompts by detecting and responding to them
     * @param output The current output from the shell
     * @param channelIn The input stream to write responses to
     * @param username The username to use for authentication
     * @param password The password to use for authentication
     * @param autoConfirm Whether to automatically confirm yes/no prompts
     * @return true if a response was sent, false otherwise
     */
    public static boolean handlePrompt(ByteArrayOutputStream output, OutputStream channelIn, 
                                      String username, String password, boolean autoConfirm) 
                                      throws IOException {
        String currentOutput = output.toString("UTF-8");
        PromptDetectionResult result = detectPrompt(currentOutput);
        
        if (!result.requiresResponse()) {
            return false;
        }
        
        switch (result.getType()) {
            case USERNAME:
                logger.info("[PROMPT-HANDLER] Responding to username prompt with: '{}'", username);
                channelIn.write((username + "\n").getBytes(StandardCharsets.UTF_8));
                channelIn.flush();
                return true;
                
            case PASSWORD:
                logger.info("[PROMPT-HANDLER] Responding to password prompt");
                channelIn.write((password + "\n").getBytes(StandardCharsets.UTF_8));
                channelIn.flush();
                return true;
                
            case YES_NO:
                if (autoConfirm) {
                    logger.info("[PROMPT-HANDLER] Auto-confirming yes/no prompt with 'yes'");
                    channelIn.write("yes\n".getBytes(StandardCharsets.UTF_8));
                    channelIn.flush();
                    return true;
                } else {
                    logger.info("[PROMPT-HANDLER] Yes/no prompt detected but auto-confirm is disabled");
                    return false;
                }
                
            default:
                return false;
        }
    }



    public static boolean handleLoginPrompt(String currentOutput, OutputStream channelIn,
                                            UserCredential userCredential, boolean autoConfirm)
            throws IOException {
        PromptDetectionResult result = detectPrompt(currentOutput);

        if (!result.requiresResponse()) {
            return false;
        }

        switch (result.getType()) {
            case USERNAME:
                logger.info("[PROMPT-HANDLER] Responding to username prompt with: '{}'", userCredential.username());
                channelIn.write((userCredential.username() + "\n").getBytes(StandardCharsets.UTF_8));
                channelIn.flush();
                return true;

            case PASSWORD:
                logger.info("[PROMPT-HANDLER] Responding to password prompt");
                channelIn.write((userCredential.password() + "\n").getBytes(StandardCharsets.UTF_8));
                channelIn.flush();
                return true;

            case YES_NO:
                if (autoConfirm) {
                    logger.info("[PROMPT-HANDLER] Auto-confirming yes/no prompt with 'yes'");
                    channelIn.write("yes\n".getBytes(StandardCharsets.UTF_8));
                    channelIn.flush();
                    return true;
                } else {
                    logger.info("[PROMPT-HANDLER] Yes/no prompt detected but auto-confirm is disabled");
                    return false;
                }

            default:
                return false;
        }
    }
    /**
     * Waits for a specific prompt type with timeout
     * @param output The output stream to monitor
     * @param promptType The type of prompt to wait for
     * @param timeoutMs Timeout in milliseconds
     * @return The detected prompt result or null if timeout
     */
    public static PromptDetectionResult waitForPrompt(ByteArrayOutputStream output, 
                                                      PromptType promptType, 
                                                      long timeoutMs) 
                                                      throws InterruptedException {
        long startTime = System.currentTimeMillis();
        String lastOutput = "";
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            String currentOutput = output.toString();
            
            // Only check if output has changed
            if (!currentOutput.equals(lastOutput)) {
                PromptDetectionResult result = detectPrompt(currentOutput);
                
                if (promptType == null || result.getType() == promptType) {
                    return result;
                }
                
                lastOutput = currentOutput;
            }
            
            Thread.sleep(100); // Check every 100ms
        }
        
        logger.warn("[PROMPT-HANDLER] Timeout waiting for prompt type: {}", promptType);
        return null;
    }
    
    /**
     * Checks if the output contains any authentication-related error messages
     */
    public static boolean hasAuthenticationError(String output) {
        if (output == null) {
            return false;
        }
        
        String lowerOutput = output.toLowerCase();
        return lowerOutput.contains("permission denied") ||
               lowerOutput.contains("authentication failed") ||
               lowerOutput.contains("invalid password") ||
               lowerOutput.contains("incorrect password") ||
               lowerOutput.contains("wrong password") ||
               lowerOutput.contains("access denied") ||
               lowerOutput.contains("login failed");
    }
    
    /**
     * Checks if the output indicates a successful authentication
     */
    public static boolean hasAuthenticationSuccess(String output) {
        if (output == null) {
            return false;
        }
        
        // Check for common success indicators
        String lowerOutput = output.toLowerCase();
        return lowerOutput.contains("welcome") ||
               lowerOutput.contains("last login") ||
               lowerOutput.contains("successful") ||
               // Or if we see a shell prompt after authentication
               detectPrompt(output).getType() == PromptType.SHELL_PROMPT;
    }
}
