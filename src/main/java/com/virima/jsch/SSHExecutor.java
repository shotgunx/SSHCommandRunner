//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.virima.jsch;

import com.jcraft.jsch.*;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;


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

    public static CommandResult executeCommand(Session session, String command, boolean isAdmin,String password) {

        int exitCode = -1;
        boolean result;
        StringBuilder output = new StringBuilder();
        Channel channel = null;
        try {

            channel = session.openChannel("exec");

            ((ChannelExec) channel).setCommand(command);





            channel.setInputStream(null);





            ((ChannelExec) channel).setErrStream(System.err);
            if(isAdmin){
                System.out.println("This command requires Admin privilage so setPty(true)");
                ((ChannelExec) channel).setPty(true);
            }


            InputStream in = channel.getInputStream();

            channel.connect();

            byte[] tmp = new byte[1024];
            while (true) {
                while (in.available() > 0) {
                    int i = in.read(tmp, 0, 1024);
                    if (i < 0)
                        break;
                    output.append(new String(tmp, 0, i));
                }
                if (channel.isClosed()) {

                    exitCode = channel.getExitStatus();
                    break;
                }
                sleepThread();
            }
            in.close();
        } catch (Exception eee) {
            Thread.currentThread().interrupt();
            eee.printStackTrace();
        }
        finally
        {
            if(channel!=null)
            {
                try
                {
                    channel.disconnect();
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        }

        result = true;

        return new CommandResult(true, exitCode, output.toString());
    }

    private static void sleepThread() {
        try {
            Thread.sleep(1000);
        } catch (Exception ee) {
            Thread.currentThread().interrupt();
            ee.printStackTrace();
        }
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
