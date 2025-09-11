//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.virima.jsch;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import com.virima.utils.AnsiStrippingInputStream;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;

public class SSHExecutor implements Callable<Session> {
    private static final String CLASSNAME = "SSHExecutor";
    String host;
    String userName;
    String password;
    String privateKey;
    String passphrase;
    int port;

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


    public static CommandResult newExecuteCommand(Session session, String command, boolean isAdmin) {
        com.jcraft.jsch.ChannelShell channel = null;
        String outputString = "";
        CommandResult result=null;
        int exitcode=-1;
        try {


            channel = (com.jcraft.jsch.ChannelShell) session.openChannel("shell");

            // Set terminal type to reduce ANSI sequences
            //channel.setPtyType("dumb", 80, 24, 640, 480);
            if(isAdmin) {
                channel.setPtyType("vt100", 80, 24, 640, 480);

                channel.setPty(true);
            }

            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();

            channel.connect(15000);

            // You can also try sending terminal configuration commands
            // For Cisco devices:
            //out.write("terminal length 0\n".getBytes()); // Disable paging
            //out.write("terminal datadump\n".getBytes());
            out.write("terminal width 512\n".getBytes()); // Set wide terminal
            out.flush();
            Thread.sleep(1000);

            // Clear output
            while (in.available() > 0) {
                in.read();
            }

            // Now detect prompt
            String prompt = detectPrompt(in, out);
            System.out.println("Detected prompt: '" + prompt + "'");

            // Execute commands

            out.write((command + "\n").getBytes());
            out.flush();

            String output = readUntilPrompt(in, out, prompt, 10000);
            System.out.printf(output);
            outputString = cleanOutput(output, command, prompt);


            out.write("exit\n".getBytes());
            out.flush();
            exitcode=channel.getExitStatus();

        } catch (Exception e) {
            e.printStackTrace();
            outputString = e.getMessage();
        } finally {
            if (channel != null) channel.disconnect();
        }

        return new CommandResult(true,exitcode,outputString);
    }

    public Session call() throws Exception {
        Session var1 = getSshSession2(this.host, this.userName, this.password, this.privateKey, this.passphrase, this.port);
        return var1;
    }

    private static String detectPromptSmartly(ByteArrayOutputStream output) {
        try {
            String previousOutput = "";
            String currentOutput = "";
            int stableCount = 0;
            int maxWaitTime = 90000;
            int waitInterval = 500;

            for(int waited = 0; waited < maxWaitTime; waited += waitInterval) {
                Thread.sleep((long)waitInterval);
                currentOutput = output.toString("UTF-8");
                if (currentOutput.equals(previousOutput)) {
                    ++stableCount;
                    if (stableCount >= 3) {
                        break;
                    }
                } else {
                    stableCount = 0;
                }

                previousOutput = currentOutput;
            }

            String[] lines = currentOutput.split("\\r?\\n");
            String detectedPrompt = "";
            String[] commonPrompts = new String[]{"\\$\\s*$", "#\\s*$", ">\\s*$", ":\\s*$", "\\]\\s*$", "\\)\\s*$"};

            for(int i = lines.length - 1; i >= 0; --i) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    for(String pattern : commonPrompts) {
                        if (line.matches(".*" + pattern)) {
                            return line;
                        }
                    }
                }
            }

            for(int i = lines.length - 1; i >= 0; --i) {
                String line = lines[i].trim();
                if (!line.isEmpty() && isLikelyPrompt(line)) {
                    return line;
                }
            }

            for(int i = lines.length - 1; i >= 0; --i) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    return line;
                }
            }

            return "";
        } catch (Exception var15) {
            String output_str = output.toString();
            String[] lines = output_str.split("\\r?\\n");

            for(int i = lines.length - 1; i >= 0; --i) {
                if (!lines[i].trim().isEmpty()) {
                    return lines[i].trim();
                }
            }

            return "";
        }
    }

    private static boolean isLikelyPrompt(String line) {
        if (line.length() > 200) {
            return false;
        } else if (line.matches("^[^a-zA-Z0-9]*$") && line.length() > 10) {
            return false;
        } else {
            boolean hasUser = line.contains("@") || line.matches(".*[a-zA-Z0-9]+.*");
            boolean hasPath = line.contains("/") || line.contains("\\") || line.contains("~");
            boolean hasPromptChar = line.matches(".*[#$>:]\\s*$");
            boolean hasHostname = line.matches(".*[a-zA-Z0-9-]+@[a-zA-Z0-9-]+.*");
            int score = 0;
            if (hasUser) {
                score += 2;
            }

            if (hasPath) {
                score += 2;
            }

            if (hasPromptChar) {
                score += 3;
            }

            if (hasHostname) {
                score += 2;
            }

            if (line.length() < 100) {
                ++score;
            }

            if (line.matches(".*\\[[a-zA-Z0-9@/~\\-\\s]+\\].*")) {
                score += 2;
            }

            return score >= 3;
        }
    }

    // Helper method to detect the shell prompt
    private static String detectPrompt(InputStream in, OutputStream out) throws IOException, InterruptedException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        BufferedReader strippingInputStream=new BufferedReader(new InputStreamReader(new AnsiStrippingInputStream(in)));

        byte[] tmp = new byte[1024];
        
        // Send a newline to get a fresh prompt
        out.write("\n".getBytes());
        out.flush();
        
        // Wait for output to stabilize
        Thread.sleep(1000);
        
//        // Read available data
//        while (strippingInputStream.available() > 0) {
//            int i = strippingInputStream.read(tmp, 0, 1024);
//            if (i < 0) break;
//            buffer.write(tmp, 0, i);
//        }

        String lline,output="";
        while ((lline = strippingInputStream.readLine()) != null) {
            output+=lline;
        }
        
        //String output = ;//buffer.toString("UTF-8");
        String[] lines = output.split("\\r?\\n");
        
        // Find the last non-empty line which is likely the prompt
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                // Check if it looks like a prompt
                if (line.matches(".*[#$>:]\\s*$") || isLikelyPrompt(line)) {
                    return line;
                }
            }
        }
        
        // Return the last non-empty line as fallback
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                return lines[i].trim();
            }
        }
        
        return "$"; // Default prompt
    }
    
    // Helper method to read output until prompt is detected
    private static String readUntilPrompt(InputStream in, OutputStream out, String prompt, int timeout) throws IOException, InterruptedException {
        StringBuilder output = new StringBuilder();
        byte[] tmp = new byte[1024];
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (in.available() > 0) {
                int i = in.read(tmp, 0, 1024);
                if (i < 0) break;
                String chunk = new String(tmp, 0, i);
                output.append(chunk);
                
                // Check if we've received the prompt
                String currentOutput = output.toString();
                if (currentOutput.contains(prompt) || currentOutput.endsWith(prompt)) {
                    break;
                }
            } else {
                Thread.sleep(100);
            }
        }
        
        return output.toString();
    }
    
    // Helper method to clean command output
    private static String cleanOutput(String output, String command, String prompt) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        
        // Remove the command echo if present
        int commandIndex = output.indexOf(command);
        if (commandIndex >= 0) {
            int endOfCommand = commandIndex + command.length();
            // Skip past the command and any trailing newline
            while (endOfCommand < output.length() && 
                   (output.charAt(endOfCommand) == '\r' || output.charAt(endOfCommand) == '\n')) {
                endOfCommand++;
            }
            output = output.substring(endOfCommand);
        }
        
        // Remove the trailing prompt if present
        if (prompt != null && !prompt.isEmpty()) {
            int lastPromptIndex = output.lastIndexOf(prompt);
            if (lastPromptIndex >= 0) {
                output = output.substring(0, lastPromptIndex);
            }
        }
        
        // Remove ANSI escape sequences
        output = output.replaceAll("\\x1B\\[[;\\d]*m", "");
        output = output.replaceAll("\\x1B\\[\\d+[A-Z]", "");
        
        return output.trim();
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
