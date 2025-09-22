package com.virima.smartRunner;

import com.virima.tes.TerminalSanitizer;
import com.virima.utils.AnsiStrippingInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.future.CancelOption;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.virima.utils.ParseUtils.detectPromptSmartly;

/**
 * SSH Command Runner that executes commands using both exec and shell channels
 * Priority: Exec channel output (if it doesn't hang), otherwise shell channel output
 */
public class SSHExecutor {
    private static final Logger logger = LoggerFactory.getLogger(SSHExecutor.class);
    private final UserCredential userCredential;
    private final ExecutorService executorService;
    private final long commandTimeoutSeconds=35;
    
    public SSHExecutor(UserCredential userCredential) {
        this.userCredential = userCredential;
        this.executorService = Executors.newFixedThreadPool(2);
    }
    

    /**
     * Execute command with priority: Exec channel first, fallback to Shell channel if exec hangs
     */
    public  CommandResult executeCommand(String command) {
        long methodStartTime = System.currentTimeMillis();
        logger.info("========================================");
        logger.info("Starting command execution");
        logger.info("Host: {}:{}", userCredential.host(), userCredential.port());
        logger.info("User: {}", userCredential.username());
        logger.info("Command: {}", command);
        logger.info("Command timeout: {} seconds", commandTimeoutSeconds);
        logger.info("========================================");
        
        SshClient client = SshClient.setUpDefaultClient();
        logger.debug("SSH client created, configuring properties...");

        client.getProperties().put("StrictHostKeyChecking", "no");
        // Fixed: Removed diffie-hellman-group-exchange-sha256 (key exchange algorithm) from authentication methods
        client.getProperties().put("PreferredAuthentications", "publickey,keyboard-interactive,password");
        client.getProperties().put("PubkeyAcceptedAlgorithms", "ssh-ed25519,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-256,rsa-sha2-512");
        client.setKeyExchangeFactories(NamedFactory.setUpTransformedFactories(true, BuiltinDHFactories.VALUES, ClientBuilder.DH2KEX));
        
        logger.debug("SSH client properties configured, starting client...");
        client.start();
        logger.debug("SSH client started successfully");

        try {
            // Try to create two sessions in parallel
            logger.info("Checking if multiple sessions can be created...");
            boolean canCreateTwoSessions = canCreateMultipleSessions(client);
            
            CommandResult result;
            if (canCreateTwoSessions) {
                logger.info("✓ Multiple sessions supported - executing in PARALLEL mode");
                result = executeParallel(client, command);
            } else {
                logger.info("✗ Multiple sessions NOT supported - executing in SEQUENTIAL mode");
                result = executeSequential(client, command);
            }
            
            long totalExecutionTime = System.currentTimeMillis() - methodStartTime;
            logger.info("========================================");
            logger.info("Command execution completed");
            logger.info("Total execution time: {} ms", totalExecutionTime);
            logger.info("Channel used: {}", result.getChannelType());
            logger.info("Success: {}", result.isSuccess());
            logger.info("Output length: {} characters", result.getOutput() != null ? result.getOutput().length() : 0);
            logger.info("Error length: {} characters", result.getError() != null ? result.getError().length() : 0);
            logger.info("========================================");
            
            return result;
        } catch (Exception e) {
            long totalExecutionTime = System.currentTimeMillis() - methodStartTime;
            logger.error("========================================");
            logger.error("Command execution FAILED after {} ms", totalExecutionTime);
            logger.error("Error type: {}", e.getClass().getSimpleName());
            logger.error("Error message: {}", e.getMessage());
            logger.error("Stack trace:", e);
            logger.error("========================================");
            return new CommandResult(null, e.getMessage(), false, "error", 0);
        } finally {
            logger.debug("Cleaning up: stopping SSH client and shutting down executor service");
            client.stop();
            executorService.shutdown();
            logger.debug("Cleanup completed");
        }
    }
    
    /**
     * Check if we can create multiple sessions simultaneously
     */
    private boolean canCreateMultipleSessions(SshClient client) {
        logger.debug("Testing multiple session creation capability...");
        try {
            logger.debug("Attempting to create first test session...");
            ClientSession session1 = connectSession(client);
            
            if (session1 == null) {
                logger.warn("Failed to create first test session");
                return false;
            }
            logger.debug("First test session created successfully");
            
            logger.debug("Attempting to create second test session...");
            ClientSession session2 = connectSession(client);
            
            boolean canCreate = session2 != null;
            
            if (canCreate) {
                logger.debug("Second test session created successfully - multiple sessions supported");
            } else {
                logger.debug("Failed to create second test session - multiple sessions not supported");
            }
            
            // Clean up test sessions
            if (session1 != null) {
                session1.close();
                logger.debug("First test session closed");
            }
            if (session2 != null) {
                session2.close();
                logger.debug("Second test session closed");
            }
            
            return canCreate;
        } catch (Exception e) {
            logger.warn("Error testing multiple session creation: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }
    
    /**
     * Execute command in parallel using both exec and shell channels
     */
    private CommandResult executeParallel(SshClient client, String command) throws Exception {
        long startTime = System.currentTimeMillis();
        logger.info("Starting parallel execution with both exec and shell channels");
        
        Future<CommandResult> execFuture = executorService.submit(() -> {
            long execStartTime = System.currentTimeMillis();
            logger.debug("[EXEC] Thread started for exec channel execution");
            try {
                logger.debug("[EXEC] Creating session for exec channel...");
                ClientSession session = connectSession(client);
                if (session == null) {
                    logger.error("[EXEC] Failed to create session for exec channel");
                    return new CommandResult(null, "Failed to create session", false, "exec", 0);
                }
                logger.debug("[EXEC] Session created successfully, executing command...");
                CommandResult result = executeWithExecChannel(session, command);
                long execTime = System.currentTimeMillis() - execStartTime;
                logger.info("[EXEC] Execution completed in {} ms, success: {}", execTime, result.isSuccess());
                return result;
            } catch (Exception e) {
                long execTime = System.currentTimeMillis() - execStartTime;
                logger.error("[EXEC] Error after {} ms: {} - {}", execTime, e.getClass().getSimpleName(), e.getMessage());
                return new CommandResult(null, e.getMessage(), false, "exec", 0);
            }
        });
        
        Future<CommandResult> shellFuture = executorService.submit(() -> {
            long shellStartTime = System.currentTimeMillis();
            logger.debug("[SHELL] Thread started for shell channel execution");
            try {
                logger.debug("[SHELL] Creating session for shell channel...");
                ClientSession session = connectSession(client);
                if (session == null) {
                    logger.error("[SHELL] Failed to create session for shell channel");
                    return new CommandResult(null, "Failed to create session", false, "shell", 0);
                }
                logger.debug("[SHELL] Session created successfully, executing command...");
                CommandResult result = newExecuteCommand(session, command, false,userCredential);
                long shellTime = System.currentTimeMillis() - shellStartTime;
                logger.info("[SHELL] Execution completed in {} ms, success: {}", shellTime, result.isSuccess());
                return result;
            } catch (Exception e) {
                long shellTime = System.currentTimeMillis() - shellStartTime;
                logger.error("[SHELL] Error after {} ms: {} - {}", shellTime, e.getClass().getSimpleName(), e.getMessage());
                return new CommandResult(null, e.getMessage(), false, "shell", 0);
            }
        });
        
        // Try to get exec result first with timeout
        logger.info("Waiting for exec channel result (timeout: {} seconds)...", commandTimeoutSeconds);
        try {
            CommandResult execResult = execFuture.get(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (execResult.isSuccess()) {
                long parallelTime = System.currentTimeMillis() - startTime;
                logger.info("✓ Exec channel succeeded after {} ms, cancelling shell channel", parallelTime);
                boolean cancelled = shellFuture.cancel(true);
                logger.debug("Shell channel cancellation: {}", cancelled ? "successful" : "failed");
                return execResult;
            } else {
                logger.warn("Exec channel completed but failed, waiting for shell channel...");
            }
        } catch (TimeoutException e) {
            long timeoutTime = System.currentTimeMillis() - startTime;
            logger.warn("✗ Exec channel timed out after {} ms, cancelling and falling back to shell channel", timeoutTime);
            boolean cancelled = execFuture.cancel(true);
            logger.debug("Exec channel cancellation: {}", cancelled ? "successful" : "failed");
        }
        
        // Fallback to shell result
        logger.info("Waiting for shell channel result (timeout: {} seconds)...", commandTimeoutSeconds);
        CommandResult shellResult = shellFuture.get(commandTimeoutSeconds, TimeUnit.SECONDS);
        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("✓ Using shell channel output after {} ms total execution time", totalTime);
        return shellResult;
    }
    
    /**
     * Execute command sequentially - shell first, then exec
     */
    private CommandResult executeSequential(SshClient client, String command) throws Exception {
        long startTime = System.currentTimeMillis();
        logger.info("Starting sequential execution (shell first, then exec if needed)");
        
        // First try shell channel
        logger.info("[SEQUENTIAL] Step 1: Attempting shell channel execution...");
        long shellStartTime = System.currentTimeMillis();
        
        ClientSession shellSession = connectSession(client);
        if (shellSession == null) {
            logger.error("[SEQUENTIAL] Failed to create shell session, aborting");
            return new CommandResult(null, "Failed to create shell session", false, "error", 0);
        }
        logger.debug("[SEQUENTIAL] Shell session created successfully");
        
        CommandResult shellResult = null;
        try {
            logger.debug("[SEQUENTIAL] Executing command via shell channel...");
            shellResult = newExecuteCommand(shellSession, command, false,userCredential);
            long shellTime = System.currentTimeMillis() - shellStartTime;
            logger.info("[SEQUENTIAL] Shell channel completed in {} ms, success: {}", 
                       shellTime, shellResult != null ? shellResult.isSuccess() : false);
        } catch (Exception e) {
            long shellTime = System.currentTimeMillis() - shellStartTime;
            logger.error("[SEQUENTIAL] Shell channel failed after {} ms: {} - {}", 
                        shellTime, e.getClass().getSimpleName(), e.getMessage());
        } finally {
            shellSession.close();
            logger.debug("[SEQUENTIAL] Shell session closed");
        }
        
        if (shellResult != null && shellResult.getOutput() != null) {
            logger.debug("[SEQUENTIAL] Shell output preview (first 200 chars): {}", 
                        shellResult.getOutput().substring(0, Math.min(200, shellResult.getOutput().length())));
        }
        
        // Then try exec channel
        logger.info("[SEQUENTIAL] Step 2: Attempting exec channel execution...");
        long execStartTime = System.currentTimeMillis();
        
        ClientSession execSession = connectSession(client);
        if (execSession == null) {
            logger.warn("[SEQUENTIAL] Failed to create exec session, returning shell result");
            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("[SEQUENTIAL] Total execution time: {} ms", totalTime);
            return shellResult != null ? shellResult : 
                   new CommandResult(null, "Failed to create exec session", false, "error", 0);
        }
        logger.debug("[SEQUENTIAL] Exec session created successfully");
        
        try {
            Future<CommandResult> execFuture = executorService.submit(() -> {
                try {
                    return executeWithExecChannel(execSession, command);
                } catch (Exception e) {
                    logger.error("Error in exec channel", e);
                    return new CommandResult(null, e.getMessage(), false, "exec", 0);
                }
            });
            
            CommandResult execResult = execFuture.get(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (execResult.isSuccess()) {
                logger.info("Exec channel succeeded, using exec output");
                return execResult;
            }
        } catch (TimeoutException e) {
            logger.warn("Exec channel timed out, using shell output");
        } finally {
            execSession.close();
        }
        
        // Return shell result as fallback
        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("[SEQUENTIAL] Returning shell result after {} ms total execution", totalTime);
        return shellResult != null ? shellResult : 
               new CommandResult(null, "Both channels failed", false, "error", 0);
    }
    
    /**
     * Create and authenticate SSH session
     */
    /*private ClientSession connectSession(SshClient client) {
        try {
            ClientSession session = client.connect(username, host, port)
                    .verify(5000, TimeUnit.MILLISECONDS)
                    .getSession();

            session.addPasswordIdentity(password);
            session.auth().verify(5000, TimeUnit.MILLISECONDS);

            return session;
        } catch (Exception e) {
            logger.error("Failed to connect session", e);
            return null;
        }
    }*/

    public  ClientSession connectSession(SshClient var6) {
        long startTime = System.currentTimeMillis();
        logger.debug("[SESSION] Creating new SSH session to {}@{}:{}", 
                    userCredential.username(), userCredential.host(), userCredential.port());

        ClientSession var7 = null;

        try {
            var6.start();
            logger.debug("[SESSION] Connecting to SSH server...");
            var7 = var6.connect(userCredential.username(), userCredential.host(), userCredential.port()).verify(30000L, new CancelOption[0]).getSession();
            if (userCredential.privateKeyFilePath() != null && !userCredential.privateKeyFilePath().isEmpty() && !userCredential.privateKeyFilePath().equalsIgnoreCase("unknown")) {
                logger.debug("[SESSION] Using private key authentication");
                File var8 = createKeyFile(userCredential.privateKeyFilePath(), userCredential.passPhrase());

                try {
                    FileKeyPairProvider var9 = new FileKeyPairProvider(Paths.get(var8.getPath()));
                    if (userCredential.passPhrase() != null && !userCredential.passPhrase().isEmpty()) {
                        logger.debug("[SESSION] Private key has passphrase");
                        var9.setPasswordFinder(FilePasswordProvider.of(userCredential.passPhrase()));
                    }

                    var7.addPublicKeyIdentity((KeyPair)var9.loadKeys(var7).iterator().next());
                    logger.debug("[SESSION] Private key loaded successfully");
                } catch (Exception var10) {
                    logger.error("[SESSION] Failed to load private key: {}", var10.getMessage());
                    throw var10;
                }
            } else {
                logger.debug("[SESSION] Using password authentication");
                var7.addPasswordIdentity(userCredential.password());
            }

            logger.debug("[SESSION] Authenticating...");
            var7.auth().verify(30000L, new CancelOption[0]);
            long connectionTime = System.currentTimeMillis() - startTime;
            logger.info("[SESSION] ✓ Session established successfully in {} ms", connectionTime);
            return var7;
        } catch (Exception var11) {
            long connectionTime = System.currentTimeMillis() - startTime;
            logger.error("[SESSION] ✗ Failed to establish session after {} ms", connectionTime);
            logger.error("[SESSION] Error: {} - {}", var11.getClass().getSimpleName(), var11.getMessage());
            logger.error("[SESSION] Host: {}:{}, User: {}", userCredential.host(), userCredential.port(), userCredential.username());
            return null;
        }
    }

    private static File createKeyFile(String var0, String var1) {
        String var2 = System.getProperty("user.dir") + File.separator + "temp" + File.separator + "ssh_privatekey.ppk";

        try {
            var0 = var0.replaceAll("\r\n", "\n");
            var0 = var0.replaceAll("\n", "\r\n");
            String var3 = System.getProperty("user.dir") + File.separator + "temp";
            if (!(new File(var3)).exists()) {
                (new File(var3)).mkdir();
            }

            var2 = var3 + File.separator + "ssh_privatekey.pem";
            FileUtils.writeStringToFile(new File(var2), var0);
            if (var1 != null) {
                var1.equals("");
            }

            return new File(var2);
        } catch (Exception var4) {
            var4.printStackTrace();
            return null;
        }
    }


    /**
     * Execute command using exec channel
     */
    private CommandResult executeWithExecChannel(ClientSession session, String command) throws Exception {
        long startTime = System.currentTimeMillis();
        logger.debug("[EXEC-CHANNEL] Starting exec channel execution");
        logger.debug("[EXEC-CHANNEL] Command: {}", command);
        
        try (ChannelExec channelExec = session.createExecChannel(command)) {
            logger.debug("[EXEC-CHANNEL] Channel created, setting up output streams...");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            
            channelExec.setOut(out);
            channelExec.setErr(err);
            
            logger.debug("[EXEC-CHANNEL] Opening channel...");
            channelExec.open().verify(5000, TimeUnit.MILLISECONDS);
            logger.debug("[EXEC-CHANNEL] Channel opened successfully, waiting for completion...");
            
            // Wait for command completion with timeout
            channelExec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 
                               TimeUnit.SECONDS.toMillis(commandTimeoutSeconds));
            
            Integer exitStatus = channelExec.getExitStatus();
            long executionTime = System.currentTimeMillis() - startTime;
            
            String output = out.toString("UTF-8");
            String error = err.toString("UTF-8");
            
            boolean success = exitStatus != null && exitStatus == 0;
            
            logger.info("[EXEC-CHANNEL] Completed in {} ms", executionTime);
            logger.info("[EXEC-CHANNEL] Exit status: {}", exitStatus);
            logger.info("[EXEC-CHANNEL] Output size: {} bytes", output.length());
            logger.info("[EXEC-CHANNEL] Error size: {} bytes", error.length());
            
            if (!success && error.length() > 0) {
                logger.warn("[EXEC-CHANNEL] Error output: {}", error.substring(0, Math.min(500, error.length())));
            }
            
            return new CommandResult(output, error, success, "exec", executionTime);
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            logger.error("[EXEC-CHANNEL] Failed after {} ms: {} - {}", 
                        executionTime, e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }
    
    /**
     * Execute command using shell channel
     */
    private CommandResult executeWithShellChannel(ClientSession session, String command) throws Exception {
        long startTime = System.currentTimeMillis();
        logger.debug("[SHELL-CHANNEL] Starting shell channel execution");
        logger.debug("[SHELL-CHANNEL] Command: {}", command);

        try (ChannelShell channelShell = session.createShellChannel()) {
            logger.debug("[SHELL-CHANNEL] Channel created, setting up streams...");
            // Use AnsiStrippingInputStream to clean output in real-time
            ByteArrayOutputStream cleanOut = new ByteArrayOutputStream();
            ByteArrayOutputStream cleanErr = new ByteArrayOutputStream();
            ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
            ByteArrayOutputStream rawErr = new ByteArrayOutputStream();
            
            // Set up streams with ANSI stripping
            channelShell.setOut(new TeeOutputStream(rawOut, new AnsiStrippingOutputStream(cleanOut)));
            channelShell.setErr(new TeeOutputStream(rawErr, new AnsiStrippingOutputStream(cleanErr)));

            channelShell.open().verify(5000, TimeUnit.MILLISECONDS);
            
            OutputStream channelIn = channelShell.getInvertedIn();
            
            // Step 1: Detect the shell prompt
            String prompt = detectShellPrompt(channelShell, channelIn, cleanOut);
            logger.info("Detected shell prompt: {}", prompt);
            
            // Step 2: Send the actual command
            logger.debug("Sending command: {}", command);
            cleanOut.reset(); // Reset output to capture only command output
            cleanErr.reset();
            channelIn.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
            channelIn.flush();
            
            // Step 3: Wait for command output (with smart detection of completion)
            String commandOutput = waitForCommandCompletion(channelShell, cleanOut, prompt, command);
            
            // Step 4: Send exit command
            logger.debug("Sending exit command");
            channelIn.write("exit\r\n".getBytes(StandardCharsets.UTF_8));
            channelIn.flush();
            
            // Step 5: Wait for channel to close
            channelShell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 
                                TimeUnit.SECONDS.toMillis(5));
            
            long executionTime = System.currentTimeMillis() - startTime;
            
            // Get error output if any
            String error = cleanErr.toString("UTF-8");
            
            // Additional cleaning with TerminalSanitizer for any remaining artifacts
            commandOutput = TerminalSanitizer.clean(commandOutput);
            
            // Remove command echo and prompt from output
            commandOutput = removeCommandEchoAndPrompt(commandOutput, command, prompt);
            
            logger.debug("Shell channel completed in {}ms", executionTime);
            
            return new CommandResult(commandOutput, error, true, "shell", executionTime);
        }
    }


    public static CommandResult newExecuteCommand(ClientSession clientSession, String script, boolean isAdmin,UserCredential userCredential) {
        long startTime = System.currentTimeMillis();
        logger.info("[NEW-EXECUTE] Starting command execution");
        logger.debug("[NEW-EXECUTE] Command: {}", script);
        logger.debug("[NEW-EXECUTE] IsAdmin: {}", isAdmin);
        String result = "";

        try (ChannelShell shell = clientSession.createShellChannel()) {
            logger.debug("[NEW-EXECUTE] Setting up channel streams...");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PipedOutputStream pipedIn = new PipedOutputStream();
            PipedInputStream pipedInput = new PipedInputStream(pipedIn);
            shell.setIn(pipedInput);
            shell.setOut(output);
            shell.setErr(output);
            
            logger.debug("[NEW-EXECUTE] Opening shell channel...");
            shell.open().verify(5L, TimeUnit.SECONDS);
            logger.debug("[NEW-EXECUTE] Shell channel opened, detecting prompt...");
            
            String prompt = detectPromptSmartly(output,pipedIn,userCredential,true);
            logger.info("[NEW-EXECUTE] Detected prompt: '{}'", prompt);

            output.reset();
            String command = script + "\nexit\n";
            logger.debug("[NEW-EXECUTE] Sending command with exit appended...");
            pipedIn.write(command.getBytes(StandardCharsets.UTF_8));
            pipedIn.flush();
            
            logger.debug("[NEW-EXECUTE] Waiting for channel to close (timeout: 10 seconds)...");
            shell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 10000L);
            
            result = output.toString("UTF-8").trim();
            logger.debug("[NEW-EXECUTE] Raw output length: {} characters", result.length());

            logger.debug("[NEW-EXECUTE] Raw output : {} ", result);

            result = prompt + result;
            if (!prompt.isEmpty() && result.contains(prompt)) {
                result = result.substring(0, result.lastIndexOf(prompt));
                logger.debug("[NEW-EXECUTE] Removed trailing prompt from output");
            }

            Pattern pattern = Pattern.compile("exit([\\s\\S]*)");
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                result = matcher.group(1);
                logger.debug("[NEW-EXECUTE] Removed 'exit' command and trailing content");
            }

            result = (String) Arrays.stream(result.split("\\r?\\n")).skip(1L).filter((line) -> !line.trim().startsWith(prompt.trim())).collect(Collectors.joining("\n"));
            
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info("[NEW-EXECUTE] Command completed in {} ms", executionTime);
            logger.debug("[NEW-EXECUTE] Final output length: {} characters", result.length());
            
            CommandResult var13 = new CommandResult(result,"",true, "shell", executionTime);
            return var13;
        } catch (IOException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            logger.error("[NEW-EXECUTE] Command execution failed after {} ms", executionTime);
            logger.error("[NEW-EXECUTE] Error type: {}", e.getClass().getSimpleName());
            logger.error("[NEW-EXECUTE] Error message: {}", e.getMessage());
            logger.error("[NEW-EXECUTE] Stack trace:", e);
            result = e.getMessage();
            return new CommandResult("",e.getMessage(),false, "shell", executionTime);
        }
    }


    /**
     * Detect the shell prompt by sending a newline and reading the response
     */
    private String detectShellPrompt(ChannelShell channel, OutputStream channelIn, 
                                    ByteArrayOutputStream out) throws Exception {
        logger.debug("[PROMPT-DETECT] Detecting shell prompt using newline method...");
        
        // Send a newline to get the prompt
        logger.debug("[PROMPT-DETECT] Sending newline to trigger prompt...");
        channelIn.write("\r\n".getBytes(StandardCharsets.UTF_8));
        channelIn.flush();
        
        // Wait a bit for the prompt to appear
        logger.debug("[PROMPT-DETECT] Waiting 500ms for prompt to appear...");
        Thread.sleep(500);
        
        String output = out.toString("UTF-8");
        String[] lines = output.split("\n");
        logger.debug("[PROMPT-DETECT] Received {} lines of output", lines.length);
        
        // The last non-empty line should be the prompt
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                // Common prompt patterns end with $, #, >, or :
                if (line.matches(".*[$#>:]\\s*$")) {
                    logger.info("[PROMPT-DETECT] Detected prompt: '{}'", line);
                    return line;
                }
            }
        }
        
        // Fallback: return the last non-empty line
        String fallbackPrompt = lines.length > 0 ? lines[lines.length - 1] : "";
        logger.warn("[PROMPT-DETECT] Using fallback prompt: '{}'", fallbackPrompt);
        return fallbackPrompt;
    }
    
    /**
     * Wait for command completion by detecting when the prompt returns
     */
    private String waitForCommandCompletion(ChannelShell channel, ByteArrayOutputStream out,
                                           String prompt, String command) throws Exception {
        StringBuilder output = new StringBuilder();
        long startWait = System.currentTimeMillis();
        long maxWait = TimeUnit.SECONDS.toMillis(commandTimeoutSeconds);
        
        logger.debug("[WAIT-COMPLETION] Waiting for command completion (timeout: {} seconds)...", commandTimeoutSeconds);
        logger.debug("[WAIT-COMPLETION] Looking for prompt: '{}'", prompt);
        
        // Clean prompt for comparison (remove ANSI sequences if any remain)
        String cleanPrompt = prompt.replaceAll("\\s+$", "");
        logger.debug("[WAIT-COMPLETION] Clean prompt for matching: '{}'", cleanPrompt);
        
        int lastOutputLength = 0;
        while (System.currentTimeMillis() - startWait < maxWait) {
            Thread.sleep(100); // Small delay to allow output to accumulate
            
            String currentOutput = out.toString("UTF-8");
            output.setLength(0);
            output.append(currentOutput);
            
            // Log progress periodically
            if (currentOutput.length() != lastOutputLength) {
                logger.trace("[WAIT-COMPLETION] Output growing: {} -> {} characters", lastOutputLength, currentOutput.length());
                lastOutputLength = currentOutput.length();
            }
            
            // Check if we see the prompt again (command completed)
            String[] lines = currentOutput.split("\n");
            if (lines.length > 1) {
                // Check last few lines for the prompt
                for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (!line.isEmpty() && 
                        (line.equals(cleanPrompt) || line.endsWith(cleanPrompt) || 
                         line.matches(".*[$#>:]\\s*$"))) {
                        // Found prompt, command likely completed
                        long completionTime = System.currentTimeMillis() - startWait;
                        logger.info("[WAIT-COMPLETION] Prompt detected after {} ms, command completed", completionTime);
                        return output.toString();
                    }
                }
            }
            
            // Also check if channel has no more data pending
            if (channel.getInvertedOut().available() == 0) {
                logger.trace("[WAIT-COMPLETION] No data pending, waiting 200ms more...");
                // Wait a bit more to ensure all data is received
                Thread.sleep(200);
                if (channel.getInvertedOut().available() == 0) {
                    long completionTime = System.currentTimeMillis() - startWait;
                    logger.debug("[WAIT-COMPLETION] No more data available after {} ms, assuming command completed", completionTime);
                    return output.toString();
                }
            }
        }
        
        long totalTime = System.currentTimeMillis() - startWait;
        logger.warn("[WAIT-COMPLETION] Command execution timed out after {} ms", totalTime);
        logger.warn("[WAIT-COMPLETION] Final output length: {} characters", output.toString().length());
        return output.toString();
    }
    
    /**
     * Remove command echo and prompt from the output
     */
    private String removeCommandEchoAndPrompt(String output, String command, String prompt) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        
        String[] lines = output.split("\n");
        StringBuilder cleaned = new StringBuilder();
        boolean foundCommand = false;
        String cleanPrompt = prompt.replaceAll("\\s+$", "");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // Skip the command echo line
            if (!foundCommand && line.contains(command)) {
                foundCommand = true;
                continue;
            }
            
            // Skip prompt lines
            if (line.trim().equals(cleanPrompt) || 
                line.trim().endsWith(cleanPrompt) ||
                (i == lines.length - 1 && line.matches(".*[$#>:]\\s*$"))) {
                continue;
            }
            
            // Skip exit command if present
            if (line.trim().equals("exit") || line.contains("logout")) {
                continue;
            }
            
            if (foundCommand || !line.trim().isEmpty()) {
                if (cleaned.length() > 0) {
                    cleaned.append("\n");
                }
                cleaned.append(line);
            }
        }
        
        return cleaned.toString().trim();
    }
    
    /**
     * Helper class to tee output to multiple streams
     */
    private static class TeeOutputStream extends OutputStream {
        private final OutputStream out1;
        private final OutputStream out2;
        
        public TeeOutputStream(OutputStream out1, OutputStream out2) {
            this.out1 = out1;
            this.out2 = out2;
        }
        
        @Override
        public void write(int b) throws IOException {
            out1.write(b);
            out2.write(b);
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out1.write(b, off, len);
            out2.write(b, off, len);
        }
        
        @Override
        public void flush() throws IOException {
            out1.flush();
            out2.flush();
        }
        
        @Override
        public void close() throws IOException {
            out1.close();
            out2.close();
        }
    }
    
    /**
     * Output stream wrapper that uses AnsiStrippingInputStream for cleaning
     */
    private static class AnsiStrippingOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer;
        private final ByteArrayOutputStream cleaned;
        
        public AnsiStrippingOutputStream(ByteArrayOutputStream target) {
            this.buffer = new ByteArrayOutputStream();
            this.cleaned = target;
        }
        
        @Override
        public void write(int b) throws IOException {
            buffer.write(b);
            processBuffer();
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            buffer.write(b, off, len);
            processBuffer();
        }
        
        private void processBuffer() throws IOException {
            byte[] data = buffer.toByteArray();
            buffer.reset();
            
            // Use AnsiStrippingInputStream to clean the data
            try (AnsiStrippingInputStream cleaner = new AnsiStrippingInputStream(
                    new java.io.ByteArrayInputStream(data))) {
                byte[] cleanBuffer = new byte[data.length];
                int bytesRead;
                while ((bytesRead = cleaner.read(cleanBuffer)) != -1) {
                    cleaned.write(cleanBuffer, 0, bytesRead);
                }
            }
        }
        
        @Override
        public void flush() throws IOException {
            processBuffer();
            cleaned.flush();
        }
        
        @Override
        public void close() throws IOException {
            processBuffer();
            cleaned.close();
        }
    }
    
    /**
     * Clean shell output by removing prompts and echo
     */
    private String cleanShellOutput(String output, String command) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        
        // Remove the command echo if present
        String[] lines = output.split("\n");
        StringBuilder cleaned = new StringBuilder();
        boolean foundCommand = false;
        
        for (String line : lines) {
            if (!foundCommand && line.contains(command)) {
                foundCommand = true;
                continue;
            }
            if (foundCommand && !line.startsWith("exit") && !line.contains("logout")) {
                if (cleaned.length() > 0) {
                    cleaned.append("\n");
                }
                cleaned.append(line);
            }
        }
        
        return cleaned.toString();
    }
    
    /**
     * Close resources
     */
    public void close() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
