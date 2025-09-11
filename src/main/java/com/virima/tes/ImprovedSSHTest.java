package com.virima.tes;

import com.virima.smartRunner.CommandResult;
import com.virima.smartRunner.SSHExecutor;
import com.virima.smartRunner.UserCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test class to demonstrate the improved SSH shell channel execution
 * with prompt detection and ANSI/control character cleaning
 */
public class ImprovedSSHTest {
    private static final Logger logger = LoggerFactory.getLogger(ImprovedSSHTest.class);
    
    public static void main(String[] args) {
        // Test configuration - update these values for your SSH server
        String host = "your-ssh-host";  // Replace with your SSH host
        String username = "your-username";  // Replace with your username
        String password = "your-password";  // Replace with your password
        int port = 22;
        
        // You can also use key-based authentication
        String privateKeyPath = null;  // Set to your private key path if using key auth
        String passPhrase = null;  // Set passphrase if key is encrypted
        
        // Create user credentials
        UserCredential credential = new UserCredential(
            host, 
            port, 
            username, 
            password, 
            privateKeyPath, 
            passPhrase
        );
        
        // Create SSH executor
        SSHExecutor executor = new SSHExecutor(credential);
        
        // Test commands
        String[] testCommands = {
            "echo 'Testing improved SSH execution'",
            "ls -la",
            "pwd",
            "echo -e '\\033[31mRed Text\\033[0m Normal Text'",  // Test ANSI color codes
            "date",
            "whoami",
            "uname -a"
        };
        
        System.out.println("========================================");
        System.out.println("Testing Improved SSH Shell Channel");
        System.out.println("========================================");
        System.out.println("Features:");
        System.out.println("1. Detects shell prompt before executing commands");
        System.out.println("2. Waits for complete command output");
        System.out.println("3. Removes ANSI escape sequences and control characters");
        System.out.println("4. Cleans command echo and prompt from output");
        System.out.println("========================================\n");
        
        for (String command : testCommands) {
            System.out.println("Executing command: " + command);
            System.out.println("----------------------------------------");
            
            try {
                CommandResult result = executor.executeCommand(command);
                
                if (result.isSuccess()) {
                    System.out.println("Output (cleaned):");
                    System.out.println(result.getOutput());
                    
                    if (result.getError() != null && !result.getError().isEmpty()) {
                        System.out.println("\nError output:");
                        System.out.println(result.getError());
                    }
                    
                    System.out.println("\nExecution time: " + result.getExecutionTimeMs() + "ms");
                    System.out.println("Channel type: " + result.getChannelType());
                } else {
                    System.err.println("Command failed!");
                    System.err.println("Error: " + result.getError());
                }
            } catch (Exception e) {
                System.err.println("Exception occurred: " + e.getMessage());
                e.printStackTrace();
            }
            
            System.out.println("========================================\n");
        }
        
        // Clean up
        executor.close();
        System.out.println("Test completed!");
    }
}
