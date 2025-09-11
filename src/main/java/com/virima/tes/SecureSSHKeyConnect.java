package com.virima.tes;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SecureSSHKeyConnect {
    public static void main(String[] args) throws Exception {
        String host = "192.168.40.223";
        int port = 22;
        String user = "root";
        String privateKeyPath = "C:/Users/MuralikrishnaSiju/Downloads/id_rsa.pem";
        String knownHostsPath = System.getProperty("user.home") + "/.ssh/known_hosts";
        String passphrase = "virima";

        // Check key file exists and validate
        Path keyFile = Paths.get(privateKeyPath);
        if (!Files.exists(keyFile)) {
            throw new RuntimeException("Key file not found: " + privateKeyPath);
        }
        
        System.out.println("Key file found: " + keyFile.toAbsolutePath());
        System.out.println("Key file size: " + keyFile.toFile().length() + " bytes");
        System.out.println("Using passphrase: " + (passphrase.isEmpty() ? "No" : "Yes"));
        
        // Check key file format and content
        try {
            List<String> lines = Files.readAllLines(keyFile);
            System.out.println("Key file first line: " + lines.get(0));
            System.out.println("Key file last line: " + lines.get(lines.size() - 1));
            System.out.println("Key file total lines: " + lines.size());
            
            // Show full content for debugging (first few lines)
            System.out.println("Key file content preview:");
            for (int i = 0; i < Math.min(lines.size(), 4); i++) {
                System.out.println("Line " + (i+1) + ": " + lines.get(i));
            }
            if (lines.size() > 4) {
                System.out.println("... (" + (lines.size() - 4) + " more lines)");
                System.out.println("Last line: " + lines.get(lines.size() - 1));
            }
            
            // Check if key appears to be encrypted
            String content = String.join("\n", lines);
            boolean isEncrypted = content.contains("Proc-Type: 4,ENCRYPTED") || 
                                content.contains("DEK-Info:") ||
                                content.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----");
            System.out.println("Key appears encrypted: " + isEncrypted);
            
            // Check for different key formats
            boolean isOpenSSH = content.contains("-----BEGIN OPENSSH PRIVATE KEY-----");
            boolean isRSAPEM = content.contains("-----BEGIN RSA PRIVATE KEY-----");
            boolean isPKCS8 = content.contains("-----BEGIN PRIVATE KEY-----");
            System.out.println("Key format - OpenSSH: " + isOpenSSH + ", RSA PEM: " + isRSAPEM + ", PKCS8: " + isPKCS8);
            
        } catch (Exception e) {
            System.err.println("Could not read key file: " + e.getMessage());
        }

        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(
                new KnownHostsServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE, Paths.get(knownHostsPath))
        );
        client.start();

        try (ClientSession session = client.connect(user, host, port)
                .verify(10, TimeUnit.SECONDS)
                .getSession()) {

            // Use SSHD's FileKeyPairProvider (following working pattern from SSHExecutor)
            FileKeyPairProvider keyProvider = new FileKeyPairProvider(Paths.get(privateKeyPath));
            if (passphrase != null && !passphrase.isEmpty()) {
                System.out.println("Setting password finder for passphrase");
                keyProvider.setPasswordFinder(FilePasswordProvider.of(passphrase));
            }
            
            try {
                System.out.println("Attempting to load keys...");
                Iterable<KeyPair> keys = keyProvider.loadKeys(session);
                
                // Check if iterator has any elements before calling next()
                if (!keys.iterator().hasNext()) {
                    throw new RuntimeException("No keys found in key provider iterator");
                }
                
                KeyPair keyPair = keys.iterator().next();
                if (keyPair == null) {
                    throw new RuntimeException("No keys loaded from: " + privateKeyPath);
                }
                System.out.println("Successfully loaded key: " + keyPair.getClass().getSimpleName());
                session.addPublicKeyIdentity(keyPair);
                System.out.println("Added public key identity to session");
            } catch (Exception e) {
                System.err.println("Failed to load key with FileKeyPairProvider: " + e.getMessage());
                e.printStackTrace();
                
                // Try without passphrase first (maybe key is not encrypted)
                try {
                    System.out.println("Trying without passphrase...");
                    FileKeyPairProvider noPassProvider = new FileKeyPairProvider(Paths.get(privateKeyPath));
                    // Don't set password finder
                    
                    Iterable<KeyPair> noPassKeys = noPassProvider.loadKeys(session);
                    if (!noPassKeys.iterator().hasNext()) {
                        throw new RuntimeException("No keys found without passphrase");
                    }
                    KeyPair noPassKeyPair = noPassKeys.iterator().next();
                    if (noPassKeyPair == null) {
                        throw new RuntimeException("No keys loaded without passphrase");
                    }
                    System.out.println("Successfully loaded key WITHOUT passphrase: " + noPassKeyPair.getClass().getSimpleName());
                    session.addPublicKeyIdentity(noPassKeyPair);
                    
                } catch (Exception noPassE) {
                    System.err.println("Failed without passphrase: " + noPassE.getMessage());
                    
                    // Try SSHExecutor.java approach - exactly like your working code
                    try {
                        System.out.println("Trying SSHExecutor.java approach...");
                        String keyContent = new String(Files.readAllBytes(Paths.get(privateKeyPath)));
                        
                        // Create temp directory and file like SSHExecutor does
                        String tempDir = System.getProperty("user.dir") + File.separator + "temp";
                        if (!Files.exists(Paths.get(tempDir))) {
                            Files.createDirectory(Paths.get(tempDir));
                        }
                        
                        String tempFilePath = tempDir + File.separator + "ssh_privatekey_test.pem";
                        Files.write(Paths.get(tempFilePath), keyContent.getBytes());
                        
                        FileKeyPairProvider sshExecProvider = new FileKeyPairProvider(Paths.get(tempFilePath));
                        if (passphrase != null && !passphrase.isEmpty()) {
                            sshExecProvider.setPasswordFinder(FilePasswordProvider.of(passphrase));
                        }
                        
                        Iterable<KeyPair> sshExecKeys = sshExecProvider.loadKeys(session);
                        if (!sshExecKeys.iterator().hasNext()) {
                            throw new RuntimeException("No keys found with SSHExecutor approach");
                        }
                        KeyPair sshExecKeyPair = sshExecKeys.iterator().next();
                        if (sshExecKeyPair == null) {
                            throw new RuntimeException("No keys loaded with SSHExecutor approach");
                        }
                        System.out.println("Successfully loaded key with SSHExecutor approach: " + sshExecKeyPair.getClass().getSimpleName());
                        session.addPublicKeyIdentity(sshExecKeyPair);
                        
                        // Clean up temp file
                        Files.deleteIfExists(Paths.get(tempFilePath));
                        
                    } catch (Exception sshExecE) {
                        System.err.println("Failed with SSHExecutor approach: " + sshExecE.getMessage());
                        
                        // Try format normalization approach
                        try {
                            System.out.println("Trying format normalization approach...");
                            String keyContent = new String(Files.readAllBytes(Paths.get(privateKeyPath)));
                            
                            // Normalize line endings and whitespace
                            String normalizedContent = keyContent.replaceAll("\\r\\n", "\n")
                                                                .replaceAll("\\r", "\n")
                                                                .trim();
                            
                            // Ensure proper line ending after header
                            if (normalizedContent.contains("-----BEGIN OPENSSH PRIVATE KEY-----")) {
                                normalizedContent = normalizedContent.replace("-----BEGIN OPENSSH PRIVATE KEY-----", 
                                                                            "-----BEGIN OPENSSH PRIVATE KEY-----\n");
                            }
                            
                            // Ensure proper line ending before footer
                            if (normalizedContent.contains("-----END OPENSSH PRIVATE KEY-----")) {
                                normalizedContent = normalizedContent.replace("-----END OPENSSH PRIVATE KEY-----", 
                                                                            "\n-----END OPENSSH PRIVATE KEY-----");
                            }
                            
                            // Create temporary file with normalized content
                            Path normalizedKeyFile = Files.createTempFile("ssh_key_normalized_", ".pem");
                            Files.write(normalizedKeyFile, normalizedContent.getBytes());
                            normalizedKeyFile.toFile().deleteOnExit();
                            
                            System.out.println("Created normalized key file: " + normalizedKeyFile.toAbsolutePath());
                            System.out.println("Normalized content length: " + normalizedContent.length());
                            
                            FileKeyPairProvider normalizedProvider = new FileKeyPairProvider(normalizedKeyFile);
                            if (passphrase != null && !passphrase.isEmpty()) {
                                normalizedProvider.setPasswordFinder(FilePasswordProvider.of(passphrase));
                            }
                            
                            Iterable<KeyPair> normalizedKeys = normalizedProvider.loadKeys(session);
                            if (!normalizedKeys.iterator().hasNext()) {
                                throw new RuntimeException("No keys found with normalized approach");
                            }
                            KeyPair normalizedKeyPair = normalizedKeys.iterator().next();
                            if (normalizedKeyPair == null) {
                                throw new RuntimeException("No keys loaded with normalized approach");
                            }
                            System.out.println("Successfully loaded key with normalized approach: " + normalizedKeyPair.getClass().getSimpleName());
                            session.addPublicKeyIdentity(normalizedKeyPair);
                            
                        } catch (Exception normalizedE) {
                            System.err.println("Failed with normalized approach: " + normalizedE.getMessage());
                            
                            // Last resort - try system temp directory approach
                            try {
                                System.out.println("Trying system temp directory approach...");
                                String keyContent = new String(Files.readAllBytes(Paths.get(privateKeyPath)));
                                
                                // Create temporary file in system temp directory
                                Path tempKeyFile = Files.createTempFile("ssh_key_", ".pem");
                                Files.write(tempKeyFile, keyContent.getBytes());
                                tempKeyFile.toFile().deleteOnExit();
                                
                                FileKeyPairProvider altProvider = new FileKeyPairProvider(tempKeyFile);
                                if (passphrase != null && !passphrase.isEmpty()) {
                                    altProvider.setPasswordFinder(FilePasswordProvider.of(passphrase));
                                }
                                
                                Iterable<KeyPair> altKeys = altProvider.loadKeys(session);
                                if (!altKeys.iterator().hasNext()) {
                                    throw new RuntimeException("No keys found with system temp approach");
                                }
                                KeyPair altKeyPair = altKeys.iterator().next();
                                if (altKeyPair == null) {
                                    throw new RuntimeException("No keys loaded with system temp approach");
                                }
                                System.out.println("Successfully loaded key with system temp approach: " + altKeyPair.getClass().getSimpleName());
                                session.addPublicKeyIdentity(altKeyPair);
                                
                            } catch (Exception altE) {
                                altE.printStackTrace();
                                throw new RuntimeException("Failed to load keys with all methods from: " + privateKeyPath + 
                                                         ". Primary error: " + e.getMessage() + 
                                                         ". No passphrase error: " + noPassE.getMessage() +
                                                         ". SSHExecutor error: " + sshExecE.getMessage() +
                                                         ". Normalized error: " + normalizedE.getMessage() +
                                                         ". System temp error: " + altE.getMessage());
                            }
                        }
                    }
                }
            }

            session.auth().verify(10, TimeUnit.SECONDS);

            String cmd = "whoami";
            try (var channel = session.createExecChannel(cmd)) {
                var baos = new java.io.ByteArrayOutputStream();
                channel.setOut(baos);
                channel.setErr(baos);
                channel.open().verify(5, TimeUnit.SECONDS);
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 10000);
                System.out.println("Output: " + baos.toString());
            }
        } finally {
            client.stop();
        }
    }
}
