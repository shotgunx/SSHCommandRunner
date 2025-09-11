package com.virima.test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

public class WorkaroundSSHTest {
    
    public static void main(String[] args) {
        System.out.println("=== SSH Connection Workaround Test ===\n");
        
        // Method 1: Use system SSH command via ProcessBuilder
        testSystemSSH();
        
        // Method 2: Use SSH with ProxyCommand
        testSSHWithProxy();
        
        // Method 3: Instructions for manual testing
        printManualInstructions();
    }
    
    private static void testSystemSSH() {
        System.out.println("Method 1: Using System SSH Command");
        System.out.println("-----------------------------------");
        
        try {
            String host = "192.168.40.63";
            String user = "root";
            String password = "Vtech@123"; // Note: This won't work with ProcessBuilder directly
            
            // For testing, we'll use SSH key or ask for manual password entry
            ProcessBuilder pb = new ProcessBuilder(
                "ssh", 
                "-o", "StrictHostKeyChecking=no",
                "-o", "ConnectTimeout=5",
                user + "@" + host,
                "echo 'Connection successful'"
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            String line;
            System.out.println("Output:");
            while ((line = reader.readLine()) != null) {
                System.out.println("  " + line);
            }
            
            int exitCode = process.waitFor();
            System.out.println("Exit code: " + exitCode);
            
        } catch (Exception e) {
            System.out.println("Failed: " + e.getMessage());
        }
    }
    
    private static void testSSHWithProxy() {
        System.out.println("\nMethod 2: SSH Configuration with ProxyCommand");
        System.out.println("----------------------------------------------");
        
        String sshConfig = """
        Host target-host
            HostName 192.168.40.63
            User root
            Port 22
            StrictHostKeyChecking no
            UserKnownHostsFile /dev/null
            # If you have a jump host, uncomment and modify:
            # ProxyCommand ssh -W %h:%p jumphost
        """;
        
        System.out.println("Add this to ~/.ssh/config:");
        System.out.println(sshConfig);
        System.out.println("\nThen connect using: ssh target-host");
    }
    
    private static void printManualInstructions() {
        System.out.println("\n=== MANUAL TESTING INSTRUCTIONS ===");
        System.out.println("====================================\n");
        
        System.out.println("Since you mentioned SSH works from your terminal, please try these commands:\n");
        
        System.out.println("1. First, verify the current connection status:");
        System.out.println("   $ ssh -v root@192.168.40.63 'echo test'");
        System.out.println("   (This will show verbose output about the connection)\n");
        
        System.out.println("2. Check which SSH client you're using:");
        System.out.println("   $ which ssh");
        System.out.println("   $ ssh -V\n");
        
        System.out.println("3. Test with sshpass for automation (install if needed: brew install sshpass):");
        System.out.println("   $ sshpass -p 'Vtech@123' ssh -o StrictHostKeyChecking=no root@192.168.40.63 'echo test'\n");
        
        System.out.println("4. Create an SSH key for passwordless authentication:");
        System.out.println("   $ ssh-keygen -t rsa -f ~/.ssh/test_key -N ''");
        System.out.println("   $ ssh-copy-id -i ~/.ssh/test_key root@192.168.40.63\n");
        
        System.out.println("5. Alternative: Use expect script for password automation:");
        System.out.println("   Create a file 'ssh_test.exp' with:");
        System.out.println("   #!/usr/bin/expect");
        System.out.println("   spawn ssh root@192.168.40.63");
        System.out.println("   expect \"password:\"");
        System.out.println("   send \"Vtech@123\\r\"");
        System.out.println("   expect \"#\"");
        System.out.println("   send \"echo 'Connected successfully'\\r\"");
        System.out.println("   send \"exit\\r\"");
        System.out.println("   expect eof\n");
        
        System.out.println("=== JAVA CODE FIX ===");
        System.out.println("====================\n");
        
        System.out.println("If the system SSH works but Java doesn't, try these fixes:\n");
        
        System.out.println("1. Run your Java application with network debugging:");
        System.out.println("   $ java -Djava.net.preferIPv4Stack=true \\");
        System.out.println("          -Djava.security.manager=default \\");
        System.out.println("          -Djava.security.policy=all.policy \\");
        System.out.println("          -cp . com.virima.old.SSHTest\n");
        
        System.out.println("2. Create an all.policy file with:");
        System.out.println("   grant {");
        System.out.println("       permission java.security.AllPermission;");
        System.out.println("   };\n");
        
        System.out.println("3. Check Java network interfaces:");
        System.out.println("   Run the following Java code to see available interfaces:\n");
    }
}
