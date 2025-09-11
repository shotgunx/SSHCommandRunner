
package com.virima;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.future.CancelOption;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.util.logging.SimplifiedLog;

public class SSHExecutor implements Callable<ClientSession> {
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

    public static ClientSession getSshSession(String var0, String var1, String var2, String var3, String var4, int var5) {
        ExecutorService var6 = Executors.newSingleThreadExecutor();
        ClientSession var7 = null;
        String var8 = "30";
        int var9 = Integer.parseInt(var8);

        ClientSession var13;
        try {
            Future var11 = var6.submit(new SSHExecutor(var0, var1, var2, var3, var4, var5));
            var7 = (ClientSession)var11.get((long)var9, TimeUnit.SECONDS);
            var13 = var7;
            return var13;
        } catch (TimeoutException var17) {
            var17.printStackTrace();
            var13 = var7;
        } catch (Exception var19) {
            Object var10 = var7;
            return (ClientSession)var10;
        } finally {
            var6.shutdown();
        }

        return var13;
    }

    public static ClientSession getSshSession2(String var0, String var1, String var2, String var3, String var4, int var5) {
        SshClient var6 = SshClient.setUpDefaultClient();
        new Properties();
        var6.getProperties().put("StrictHostKeyChecking", "no");
        var6.getProperties().put("compression.s2c", "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr,aes128-gcm");
        var6.getProperties().put("compression.c2s", "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr,aes128-gcm");
        var6.getProperties().put("PreferredAuthentications", "publickey,keyboard-interactive,password");
        var6.getProperties().put("PubkeyAcceptedAlgorithms", "ssh-ed25519,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-256,rsa-sha2-512");

        try {
            var6.start();
            ClientSession var7 = (ClientSession)((ConnectFuture)var6.connect(var1, var0, var5).verify(30000L, new CancelOption[0])).getSession();
            if (var3 != null && !var3.isEmpty() && !var3.equalsIgnoreCase("unknown")) {
                File var8 = createKeyFile(var3, var4);
                FileKeyPairProvider var9 = new FileKeyPairProvider(var8.toPath());
                var7.addPublicKeyIdentity((KeyPair)var9.loadKeys(var7).iterator().next());
            } else {
                var7.addPasswordIdentity(var2);
            }

            var7.auth().verify(30000L, new CancelOption[0]);
            ClientSession var15 = var7;
            return var15;
        } catch (Exception var13) {
            var13.printStackTrace();
            System.out.println("SSHExecutorGetting session for SSH for host " + var0 + " exception with port " + var5 + ". Message : " + var13.getMessage());
        } finally {
            var6.stop();
        }

        return null;
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

    public static ClientSession getSshSessionForHost(String var0, String var1, String var2, String var3, String var4, String var5) {
        SshClient var6 = SshClient.setUpDefaultClient();
        ClientSession var7 = null;
        var6.getProperties().put("StrictHostKeyChecking", "no");
        var6.getProperties().put("PreferredAuthentications", "diffie-hellman-group-exchange-sha256,publickey,keyboard-interactive,password");
        var6.getProperties().put("PubkeyAcceptedAlgorithms", "ssh-ed25519,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-256,rsa-sha2-512");
        var6.setKeyExchangeFactories(NamedFactory.setUpTransformedFactories(true, BuiltinDHFactories.VALUES, ClientBuilder.DH2KEX));

        try {
            var6.start();
            var7 = (ClientSession)((ConnectFuture)var6.connect(var1, var0, Integer.parseInt(var5)).verify(30000L, new CancelOption[0])).getSession();
            if (var3 != null && !var3.isEmpty() && !var3.equalsIgnoreCase("unknown")) {
                File var8 = createKeyFile(var3, var4);

                try {
                    FileKeyPairProvider var9 = new FileKeyPairProvider(Paths.get(var8.getPath()));
                    if (var4 != null && !var4.isEmpty()) {
                        var9.setPasswordFinder(FilePasswordProvider.of(var4));
                    }

                    var7.addPublicKeyIdentity((KeyPair)var9.loadKeys(var7).iterator().next());
                } catch (Exception var10) {
                    var10.printStackTrace();
                }
            } else {
                var7.addPasswordIdentity(var2);
            }

            var7.auth().verify(30000L, new CancelOption[0]);
            return var7;
        } catch (Exception var11) {
            var11.printStackTrace();
            System.out.println("SSHExecutorGetting session for SSH for host " + var0 + " exception with port " + var5 + ". Message : " + var11.getMessage());
            return null;
        }
    }

    public static CommandResult executeCommand(ClientSession var0, String var1, boolean var2) throws Throwable {
        int[] var3 = new int[]{-1};
        boolean var4 = false;
        StringBuilder var5 = new StringBuilder();
        ClientChannel[] var6 = new ClientChannel[1];

        try {
            System.out.println(var1);
            var6[0] = var0.createShellChannel();
            OpenFuture var7 = var6[0].open();
            var7.await(new CancelOption[0]);
            Throwable var8 = null;
            Object var9 = null;

            try {
                OutputStream var10 = var6[0].getInvertedIn();

                try {
                    InputStream var11 = var6[0].getInvertedOut();

                    try {
                        InputStream var12 = var6[0].getInvertedErr();

                        try {
                            byte[] var13 = new byte[1024];

                            while(true) {
                                if (var11.available() > 0) {
                                    int var14 = var11.read(var13);
                                    if (var14 >= 0) {
                                        var5.append(new String(var13, 0, var14));
                                        continue;
                                    }
                                }

                                while(var12.available() > 0) {
                                    var12.read(var13);
                                }

                                if (var6[0].isClosed()) {
                                    var3[0] = var6[0].getExitStatus();
                                    break;
                                }

                                try {
                                    Thread.sleep(1000L);
                                } catch (InterruptedException var37) {
                                    var37.printStackTrace();
                                }
                            }
                        } finally {
                            if (var12 != null) {
                                var12.close();
                            }

                        }
                    } catch (Throwable var39) {
                        if (var8 == null) {
                            var8 = var39;
                        } else if (var8 != var39) {
                            var8.addSuppressed(var39);
                        }

                        if (var11 != null) {
                            var11.close();
                        }

                        throw var8;
                    }

                    if (var11 != null) {
                        var11.close();
                    }
                } catch (Throwable var40) {
                    if (var8 == null) {
                        var8 = var40;
                    } else if (var8 != var40) {
                        var8.addSuppressed(var40);
                    }

                    if (var10 != null) {
                        var10.close();
                    }

                    throw var8;
                }

                if (var10 != null) {
                    var10.close();
                }
            } catch (Throwable var41) {
                if (var8 == null) {
                    var8 = var41;
                } else if (var8 != var41) {
                    var8.addSuppressed(var41);
                }

                throw var8;
            }
        } catch (IOException var42) {
            var42.printStackTrace();
        } finally {
            if (var6[0] != null) {
                try {
                    var6[0].close(false).await(new CancelOption[0]);
                } catch (Exception var36) {
                    var36.printStackTrace();
                }
            }

        }

        return new CommandResult(var4, var3[0], var5.toString());
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

            result = (String)Arrays.stream(result.split("\\r?\\n")).skip(1L).filter((line) -> !line.trim().startsWith(prompt.trim())).collect(Collectors.joining("\n"));
            CommandResult var13 = new CommandResult(true, 0, result);
            return var13;
        } catch (IOException e) {
            result = e.getMessage();
            return new CommandResult(false, -1, result);
        }
    }

    public ClientSession call() throws Exception {
        ClientSession var1 = getSshSession2(this.host, this.userName, this.password, this.privateKey, this.passphrase, this.port);
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

    public static class MyLogger implements SimplifiedLog {
        public boolean isEnabledLevel(Level var1) {
            return true;
        }

        public void log(Level var1, Object var2, Throwable var3) {
            System.out.println(var1.intValue());
            System.out.println(var1.getName());
        }
    }
}
