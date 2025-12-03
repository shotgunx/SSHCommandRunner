//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.virima.jsch;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import net.sf.expectit.Expect;
import net.sf.expectit.ExpectBuilder;
import net.sf.expectit.Result;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static net.sf.expectit.filter.Filters.removeColors;
import static net.sf.expectit.filter.Filters.removeNonPrintable;
import static net.sf.expectit.matcher.Matchers.*;

public class SSHExecutor implements Callable<Session> {
    private static final String CLASSNAME = "SSHExecutor";
    String host;
    String userName;
    String password;
    String privateKey;
    String passphrase;
    int port;

    // Regex pattern to match common shell prompts:
    // - username@host:path$ (Linux)
    // - username@host:path# (root)
    // - [user@host ~]$ (CentOS/RHEL)
    // - hostname> or hostname# (network devices)
    // - PS C:\> (PowerShell)
    static final String PROMPT_REGEX = ".*[#>$%]\\s*$";

    public SSHExecutor(String var1, String var2, String var3, String var4, String var5, int var6) {
        this.host = var1;
        this.userName = var2;
        this.password = var3;
        this.privateKey = var4;
        this.passphrase = var5;
        this.port = var6;
    }


    public static Session getSshSession2(String host, String userName,
                                         String password, String privateKey, String passphrase, int port) {
        JSch jsch = new JSch();

        JSch.setLogger(new MyLogger());
        Properties config = getProperties();

        Session session;
        String ppkFileLocation = null;

        try {
            session = jsch.getSession(userName, host);
            session.setConfig(config);
            session.setPort(port);

            if (privateKey != null && !privateKey.isEmpty() && !privateKey.equalsIgnoreCase("unknown")){

                privateKey = privateKey.replace("\r\n", "\n");
                privateKey = privateKey.replace("\n", "\r\n");

                String cwd = System.getProperty("user.dir") + File.separator + "temp";
                if (!new File(cwd).exists())
                    new File(cwd).mkdir();

                ppkFileLocation = cwd + File.separator + UUID.randomUUID() + ".ppk";
                try {
                    FileUtils.writeStringToFile(new File(ppkFileLocation), privateKey, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
                if(passphrase != null && !passphrase.isEmpty()){
                    jsch.addIdentity(ppkFileLocation, passphrase);
                } else {
                    jsch.addIdentity(ppkFileLocation);
                }

            } else {
                UserInfo ui = new CustomUserInfo(password);
                session.setUserInfo(ui);
                session.setPassword(password);
            }
            session.connect(30000);
        } catch (JSchException e) {
            System.err.println("ERROR: ["+CLASSNAME+"] "+e);
            return null;
        } finally {
            if(ppkFileLocation != null)
                FileUtils.deleteQuietly(new File(ppkFileLocation));
        }
        return session;
    }

    private static Properties getProperties() {
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "publickey,keyboard-interactive,gssapi-keyex,gssapi-with-mic,password");

        //config.put("mac.c2s", "hmac-sha2-256,hmac-sha2-512,hmac-sha1");
        //config.put("mac.s2c", "hmac-sha2-256,hmac-sha2-512,hmac-sha1");


        String jschProposal = "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr,aes128-gcm";
        String serverProposal = "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr,aes128-gcm";


        config.put("cipher.c2s", jschProposal);

        config.put("cipher.s2c", serverProposal);


        config.put("PubkeyAcceptedAlgorithms", "ssh-ed25519,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-256,rsa-sha2-512");


        config.put("server_host_key", "ssh-ed25519,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-256,rsa-sha2-512");


        config.put("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha256,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1,curve25519-sha256,diffie-hellman-group14-sha256");
        return config;
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

    public static Session getSshSessionForHost(String host, String userName, String password, String privateKey, String passphrase, String portStr) {
        // Use the existing getSshSession2 method which already uses JSch
        int port = Integer.parseInt(portStr);
        return getSshSession2(host, userName, password, privateKey, passphrase, port);
    }


    /**
     * Executes a command on the SSH session using ExpectIt library for reliable prompt detection.
     * 
     * Flow:
     * ┌─────────────────────────────────────────────────────────────┐
     * │  1. Open Shell Channel                                      │
     * │         ↓                                                   │
     * │  2. Create ExpectIt instance with filters                   │
     * │     (removes ANSI colors & non-printable chars)             │
     * │         ↓                                                   │
     * │  3. Wait for initial prompt (detect prompt pattern)         │
     * │         ↓                                                   │
     * │  4. Send command                                            │
     * │         ↓                                                   │
     * │  5. Wait for prompt again (captures command output)         │
     * │         ↓                                                   │
     * │  6. Extract & clean output                                  │
     * │         ↓                                                   │
     * │  7. Send exit & cleanup                                     │
     * └─────────────────────────────────────────────────────────────┘
     */
    public static CommandResult newExecuteCommand(Session session, String command, boolean isAdmin,String password) {
        com.jcraft.jsch.ChannelShell channel = null;
        Expect expect = null;
        String outputString = "";
        int exitcode = -1;
        

        
        try {
            channel = (com.jcraft.jsch.ChannelShell) session.openChannel("shell");
            
            // Set terminal type based on admin mode
            if (isAdmin) {
                channel.setPtyType("vt100", 200, 24, 640, 480);
                channel.setPty(true);
            } else {
                // Use dumb terminal to minimize escape sequences
                channel.setPtyType("dumb", 200, 24, 640, 480);
            }
            
            channel.connect(15000);
            
            // Build ExpectIt instance with filters to clean output
            expect = new ExpectBuilder()
                    .withOutput(channel.getOutputStream())
                    .withInputs(channel.getInputStream())
                    .withTimeout(60, TimeUnit.SECONDS)        // Default timeout for expect operations
                    .withInputFilters(removeColors(), removeNonPrintable())  // Strip ANSI codes
                    .build();


            // Step 4: Handle login sequence (username/password if asked again)
            handleLogin(expect,session.getUserName(), password);

            String detectedPrompt = detectPrompt(expect);
            System.out.println("Detected prompt: '" + detectedPrompt + "'");
            
            // Build a regex pattern that matches this specific prompt (escape special regex chars)
            String escapedPrompt = Pattern.quote(detectedPrompt);


            drainBuffer(expect,100);

            StringBuilder output = new StringBuilder();
            expect.sendLine(command);

            while (true) {
                Result result = expect.expect(
                        5*1000L,
                        anyOf(
                                regexp("(?im)--\\s*more\\s*--"),
                                regexp("(?im)<---\\s*more\\s*--->"),
                                regexp("(?im)^.*More:.*<space>.*Quit:.*q.*CTRL.*$"),
                                regexp("(?m).*" + escapedPrompt + "\\s*$")
                        )
                );

                if(result.isSuccessful())
                    output.append(result.getBefore().stripLeading());
                else
                    break;

                if (result.getInput().toLowerCase().contains("more")) {
                    expect.send(" ");  // Space to continue
                } else {
                    break;
                }
            }

            outputString= cleanOutput(output.toString());



            System.out.println("=== Command Output ===");
            System.out.println(outputString);
            System.out.println("======================");
            
            // Send exit command
            expect.sendLine("exit");
            
            // Small delay to allow exit to process
            Thread.sleep(500);
            
            exitcode = channel.getExitStatus();
            
        } catch (Exception e) {
            e.printStackTrace();
            outputString = "Error: " + e.getMessage();
        } finally {
            // Clean up resources
            if (expect != null) {
                try {
                    expect.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }
            if (channel != null) {
                channel.disconnect();
            }
        }
        
        return new CommandResult(true, exitcode, outputString);
    }

    // ==================== LOGIN HANDLING ====================

    private static void handleLogin(Expect expect,String username, String password) throws IOException {
        int maxAttempts = 1;

        for (int i = 0; i < maxAttempts; i++) {
            Result result = expect.withTimeout(5000L, TimeUnit.MILLISECONDS).expect(
                    anyOf(
                            regexp("(?i)(username|login|user)\\s*:\\s*$"),      // Username prompt
                            regexp("(?i)password\\s*:\\s*$"),                    // Password prompt
                            regexp("(?i)(authentication failed|access denied|login incorrect)"), // Failure
                            regexp("[\\w\\-\\.@]+[#>$%]\\s*$")                  // Device prompt
                    )
            );

            if (!result.isSuccessful()) {
                // Timeout - send enter to trigger prompt
                expect.sendLine("");
                continue;
            }

            String matched = result.getInput().toLowerCase().trim();

            // Auth failure
            if (matched.contains("authentication failed") ||
                    matched.contains("access denied") ||
                    matched.contains("login incorrect")) {
                throw new IOException("Authentication failed");
            }

            // Username prompt
            if (matched.contains("username") || matched.contains("login") ||
                    matched.matches(".*user\\s*:.*")) {
                expect.sendLine(username);
                continue;
            }

            // Password prompt
            if (matched.contains("password")) {
                expect.sendLine(password);
                continue;
            }

            // Device prompt - login successful
            if (matched.matches(".*[#>$%]\\s*$")) {
                return;
            }
        }
    }


    /**
     * Detects the shell prompt by waiting for output that matches common prompt patterns.
     * 
     * Strategy:
     * ┌────────────────────────────────────────────────────────────┐
     * │  1. Wait for initial prompt (login banner + prompt)        │
     * │         ↓                                                  │
     * │  2. Send empty line to get fresh prompt                    │
     * │         ↓                                                  │
     * │  3. Wait for prompt pattern again                          │
     * │         ↓                                                  │
     * │  4. Extract last line matching prompt pattern              │
     * └────────────────────────────────────────────────────────────┘
     */
    private static String detectPrompt(Expect expect) throws IOException {
        // Clear buffer
        drainBuffer(expect,500);

        // Send enter to get fresh prompt
        expect.sendLine("");

        try { Thread.sleep(1000); } catch (InterruptedException e) { }

        // Collect response
        StringBuilder response = new StringBuilder();
        while (true) {
            Result result = expect.withTimeout(500,TimeUnit.MILLISECONDS).expect( anyString());
            if (result.isSuccessful()) {
                response.append(result.getInput());
            } else {
                break;
            }
        }

        // Extract prompt from last line
        String[] lines = response.toString().split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && line.matches(".*[#>$%]\\s*$")) {
                return line;

            }
        }

        throw new IOException("Could not detect device prompt");
    }
    private static void drainBuffer(Expect expect, int timeoutMs) {
        try {
            while (expect.expect(timeoutMs, anyString()).isSuccessful()) {
                // Drain
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    /**
     * Cleans the command output captured by ExpectIt.
     * Removes command echo and any trailing whitespace.
     */
    private static String cleanExpectOutput(String output, String command, String prompt) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        
        String[] lines = output.split("\\r?\\n");
        StringBuilder cleaned = new StringBuilder();
        boolean foundCommand = false;
        
        for (String line : lines) {
            // Skip the echoed command line
            if (!foundCommand && line.contains(command)) {
                foundCommand = true;
                continue;
            }
            
            // Skip empty lines at the beginning
            if (!foundCommand && line.trim().isEmpty()) {
                continue;
            }
            
            // Skip if line matches the prompt
            if (prompt != null && line.trim().equals(prompt.trim())) {
                continue;
            }
            
            if (foundCommand) {
                if (cleaned.length() > 0) {
                    cleaned.append("\n");
                }
                cleaned.append(line);
            }
        }
        
        return cleaned.toString().trim();
    }


    private static String cleanOutput(String output) {
        if (output == null) return "";

        // Remove command echo (first line)
        int firstNewline = output.indexOf('\n');
        if (firstNewline > 0) {
            output = output.substring(firstNewline + 1);
        }

        // Clean whitespace
        output = output.replaceAll("\\r", "");
        output = output.replaceAll("\\n{3,}", "\n\n");

        return output.trim();
    }
    public Session call() throws Exception {
        Session var1 = getSshSession2(this.host, this.userName, this.password, this.privateKey, this.passphrase, this.port);
        return var1;
    }

    public static class MyLogger implements com.jcraft.jsch.Logger {
        public boolean isEnabled(int level) {
            return true;
        }

        public void log(int level, String message) {
            System.out.println("JSch [" + level + "]: " + message);
        }
    }
}
