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

import static com.virima.old.SSHExecutor.detectPromptSmartly;

/**
 * SSH Command Runner that executes commands using both exec and shell channels
 * Priority: Exec channel output (if it doesn't hang), otherwise shell channel output
 */
public class SSHExecutor {
    private static final Logger logger = LoggerFactory.getLogger(SSHExecutor.class);
    private final UserCredential userCredential;
    private final ExecutorService executorService;
    private final long commandTimeoutSeconds=10;
    
    public SSHExecutor(UserCredential userCredential) {
        this.userCredential = userCredential;
        this.executorService = Executors.newFixedThreadPool(2);
    }
    

    /**
     * Execute command with priority: Exec channel first, fallback to Shell channel if exec hangs
     */
    public  CommandResult executeCommand(String command) {
        logger.info("Executing command: {}", command);
        
        SshClient client = SshClient.setUpDefaultClient();

        client.getProperties().put("StrictHostKeyChecking", "no");
        client.getProperties().put("PreferredAuthentications", "diffie-hellman-group-exchange-sha256,publickey,keyboard-interactive,password");
        client.getProperties().put("PubkeyAcceptedAlgorithms", "ssh-ed25519,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-256,rsa-sha2-512");
        client.setKeyExchangeFactories(NamedFactory.setUpTransformedFactories(true, BuiltinDHFactories.VALUES, ClientBuilder.DH2KEX));

        client.start();

        try {
            // Try to create two sessions in parallel
            boolean canCreateTwoSessions = canCreateMultipleSessions(client);
            
            if (canCreateTwoSessions) {
                logger.info("Can create multiple sessions - running exec and shell in parallel");
                return executeParallel(client, command);
            } else {
                logger.info("Cannot create multiple sessions - running sequentially (shell first, then exec)");
                return executeSequential(client, command);
            }
        } catch (Exception e) {
            logger.error("Error executing command", e);
            return new CommandResult(null, e.getMessage(), false, "error", 0);
        } finally {
            client.stop();
            executorService.shutdown();
        }
    }
    
    /**
     * Check if we can create multiple sessions simultaneously
     */
    private boolean canCreateMultipleSessions(SshClient client) {
        try {
            ClientSession session1 = connectSession(client);
            ClientSession session2 = connectSession(client);
            
            boolean canCreate = session1 != null && session2 != null;
            
            if (session1 != null) session1.close();
            if (session2 != null) session2.close();
            
            return canCreate;
        } catch (Exception e) {
            logger.warn("Cannot create multiple sessions: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Execute command in parallel using both exec and shell channels
     */
    private CommandResult executeParallel(SshClient client, String command) throws Exception {
        Future<CommandResult> execFuture = executorService.submit(() -> {
            try {
                ClientSession session = connectSession(client);
                if (session == null) {
                    return new CommandResult(null, "Failed to create session", false, "exec", 0);
                }
                return executeWithExecChannel(session, command);
            } catch (Exception e) {
                logger.error("Error in exec channel", e);
                return new CommandResult(null, e.getMessage(), false, "exec", 0);
            }
        });
        
        Future<CommandResult> shellFuture = executorService.submit(() -> {
            try {
                ClientSession session = connectSession(client);
                if (session == null) {
                    return new CommandResult(null, "Failed to create session", false, "shell", 0);
                }
                return newExecuteCommand(session, command,false);
            } catch (Exception e) {
                logger.error("Error in shell channel", e);
                return new CommandResult(null, e.getMessage(), false, "shell", 0);
            }
        });
        
        // Try to get exec result first with timeout
        try {
            CommandResult execResult = execFuture.get(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (execResult.isSuccess()) {
                logger.info("Exec channel succeeded, using exec output");
                shellFuture.cancel(true); // Cancel shell execution
                return execResult;
            }
        } catch (TimeoutException e) {
            logger.warn("Exec channel timed out, falling back to shell channel");
            execFuture.cancel(true);
        }
        
        // Fallback to shell result
        CommandResult shellResult = shellFuture.get(commandTimeoutSeconds, TimeUnit.SECONDS);
        logger.info("Using shell channel output");
        return shellResult;
    }
    
    /**
     * Execute command sequentially - shell first, then exec
     */
    private CommandResult executeSequential(SshClient client, String command) throws Exception {
        // First try shell channel
        ClientSession shellSession = connectSession(client);
        if (shellSession == null) {
            return new CommandResult(null, "Failed to create shell session", false, "error", 0);
        }
        
        CommandResult shellResult = null;
        try {
            shellResult = newExecuteCommand(shellSession, command,false);
        } catch (Exception e) {
            logger.error("Shell channel failed", e);
        } finally {
            shellSession.close();
        }
        System.out.println("shell:"+ shellResult.getOutput() +"\n ----------------");
        
        // Then try exec channel
        ClientSession execSession = connectSession(client);
        if (execSession == null) {
            return shellResult != null ? shellResult : 
                   new CommandResult(null, "Failed to create exec session", false, "error", 0);
        }
        
        /*try {
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
        }*/
        
        // Return shell result as fallback
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

        ClientSession var7 = null;

        try {
            var6.start();
            var7 = var6.connect(userCredential.username(), userCredential.host(), userCredential.port()).verify(30000L, new CancelOption[0]).getSession();
            if (userCredential.privateKeyFilePath() != null && !userCredential.privateKeyFilePath().isEmpty() && !userCredential.privateKeyFilePath().equalsIgnoreCase("unknown")) {
                File var8 = createKeyFile(userCredential.privateKeyFilePath(), userCredential.passPhrase());

                try {
                    FileKeyPairProvider var9 = new FileKeyPairProvider(Paths.get(var8.getPath()));
                    if (userCredential.passPhrase() != null && !userCredential.passPhrase().isEmpty()) {
                        var9.setPasswordFinder(FilePasswordProvider.of(userCredential.passPhrase()));
                    }

                    var7.addPublicKeyIdentity((KeyPair)var9.loadKeys(var7).iterator().next());
                } catch (Exception var10) {
                    throw var10;
                    //var10.printStackTrace();
                }
            } else {
                var7.addPasswordIdentity(userCredential.password());
            }

            var7.auth().verify(30000L, new CancelOption[0]);
            return var7;
        } catch (Exception var11) {
            var11.printStackTrace();
            System.out.println("SSHExecutorGetting session for SSH for host " + userCredential.host() + " exception with port " + userCredential.port() + ". Message : " + var11.getMessage());
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
        logger.debug("Executing command with exec channel: {}", command);
        
        try (ChannelExec channelExec = session.createExecChannel(command)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            
            channelExec.setOut(out);
            channelExec.setErr(err);
            
            channelExec.open().verify(5000, TimeUnit.MILLISECONDS);
            
            // Wait for command completion with timeout
            channelExec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 
                               TimeUnit.SECONDS.toMillis(commandTimeoutSeconds));
            
            Integer exitStatus = channelExec.getExitStatus();
            long executionTime = System.currentTimeMillis() - startTime;
            
            String output = out.toString(StandardCharsets.UTF_8);
            String error = err.toString(StandardCharsets.UTF_8);
            
            boolean success = exitStatus != null && exitStatus == 0;
            
            logger.debug("Exec channel completed in {}ms with exit status: {}", executionTime, exitStatus);
            
            return new CommandResult(output, error, success, "exec", executionTime);
        }
    }
    
    /**
     * Execute command using shell channel
     */
    private CommandResult executeWithShellChannel(ClientSession session, String command) throws Exception {
        long startTime = System.currentTimeMillis();
        logger.debug("Executing command with shell channel: {}", command);

        try (ChannelShell channelShell = session.createShellChannel()) {
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
            String error = cleanErr.toString(StandardCharsets.UTF_8);
            
            // Additional cleaning with TerminalSanitizer for any remaining artifacts
            commandOutput = TerminalSanitizer.clean(commandOutput);
            
            // Remove command echo and prompt from output
            commandOutput = removeCommandEchoAndPrompt(commandOutput, command, prompt);
            
            logger.debug("Shell channel completed in {}ms", executionTime);
            
            return new CommandResult(commandOutput, error, true, "shell", executionTime);
        }
    }


    public static CommandResult newExecuteCommand(ClientSession clientSession, String script, boolean isAdmin) {
        String result = "";

        try (ChannelShell shell = clientSession.createShellChannel()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PipedOutputStream pipedIn = new PipedOutputStream();
            PipedInputStream pipedInput = new PipedInputStream(pipedIn);
            shell.setIn(pipedInput);
            shell.setOut(output);
            shell.setErr(output);
            shell.open().verify(5L, TimeUnit.SECONDS);
            String prompt = detectPromptSmartly(output);
            output.reset();
            String command = script + "\nexit\n";
            pipedIn.write(command.getBytes(StandardCharsets.UTF_8));
            pipedIn.flush();
            shell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 10000L);
            result = output.toString("UTF-8").trim();
            result = prompt + result;
            if (!prompt.isEmpty() && result.contains(prompt)) {
                result = result.substring(0, result.lastIndexOf(prompt));
            }

            Pattern pattern = Pattern.compile("exit([\\s\\S]*)");
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                result = matcher.group(1);
            }

            result = (String) Arrays.stream(result.split("\\r?\\n")).skip(1L).filter((line) -> !line.trim().startsWith(prompt.trim())).collect(Collectors.joining("\n"));
            CommandResult var13 = new CommandResult(result,"",true, "shell", 0);
            return var13;
        } catch (IOException e) {
            result = e.getMessage();
            return new CommandResult("",e.getMessage(),false, "shell", 0);
        }
    }


    /**
     * Detect the shell prompt by sending a newline and reading the response
     */
    private String detectShellPrompt(ChannelShell channel, OutputStream channelIn, 
                                    ByteArrayOutputStream out) throws Exception {
        // Send a newline to get the prompt
        channelIn.write("\r\n".getBytes(StandardCharsets.UTF_8));
        channelIn.flush();
        
        // Wait a bit for the prompt to appear
        Thread.sleep(500);
        
        String output = out.toString(StandardCharsets.UTF_8);
        String[] lines = output.split("\n");
        
        // The last non-empty line should be the prompt
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                // Common prompt patterns end with $, #, >, or :
                if (line.matches(".*[$#>:]\\s*$")) {
                    return line;
                }
            }
        }
        
        // Fallback: return the last non-empty line
        return lines.length > 0 ? lines[lines.length - 1] : "";
    }
    
    /**
     * Wait for command completion by detecting when the prompt returns
     */
    private String waitForCommandCompletion(ChannelShell channel, ByteArrayOutputStream out,
                                           String prompt, String command) throws Exception {
        StringBuilder output = new StringBuilder();
        long startWait = System.currentTimeMillis();
        long maxWait = TimeUnit.SECONDS.toMillis(commandTimeoutSeconds);
        
        // Clean prompt for comparison (remove ANSI sequences if any remain)
        String cleanPrompt = prompt.replaceAll("\\s+$", "");
        
        while (System.currentTimeMillis() - startWait < maxWait) {
            Thread.sleep(100); // Small delay to allow output to accumulate
            
            String currentOutput = out.toString(StandardCharsets.UTF_8);
            output.setLength(0);
            output.append(currentOutput);
            
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
                        logger.debug("Command completed - prompt detected");
                        return output.toString();
                    }
                }
            }
            
            // Also check if channel has no more data pending
            if (channel.getInvertedOut().available() == 0) {
                // Wait a bit more to ensure all data is received
                Thread.sleep(200);
                if (channel.getInvertedOut().available() == 0) {
                    logger.debug("No more data available, assuming command completed");
                    return output.toString();
                }
            }
        }
        
        logger.warn("Command execution timed out after {} seconds", commandTimeoutSeconds);
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
