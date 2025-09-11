package com.virima.smartRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * Main class to demonstrate SSH Command Runner usage
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    public static void main(String[] args) {
        // Default connection parameters (can be overridden by command line args)
        String host = "192.168.1.46";
        int port = 22;
        String username = "chath";
        String password = "Embakkam#1";
        long timeoutSeconds = 10;
        
        // Parse command line arguments if provided
        if (args.length >= 4) {
            host = args[0];
            port = Integer.parseInt(args[1]);
            username = args[2];
            password = args[3];
            if (args.length >= 5) {
                timeoutSeconds = Long.parseLong(args[4]);
            }
        } else if (args.length > 0 && args.length < 4) {
            System.out.println("Usage: java -jar ssh-command-runner.jar [host] [port] [username] [password] [timeout_seconds]");
            System.out.println("Or run without arguments for interactive mode");
            System.exit(1);
        }
        
        // Interactive mode if no arguments provided
        if (args.length == 0) {
            Scanner scanner = new Scanner(System.in);
            
            System.out.println("=== SSH Command Runner - Interactive Mode ===");
            System.out.print("Enter SSH host (default: localhost): ");
            String input = scanner.nextLine().trim();
            if (!input.isEmpty()) host = input;
            
            System.out.print("Enter SSH port (default: 22): ");
            input = scanner.nextLine().trim();
            if (!input.isEmpty()) port = Integer.parseInt(input);
            
            System.out.print("Enter username: ");
            input = scanner.nextLine().trim();

            if (!input.isEmpty()) username = input.trim();

            System.out.print("Enter password: ");
            input = scanner.nextLine().trim();

            if (!input.isEmpty()) password = input.trim();

            System.out.print("Enter command timeout in seconds (default: 10): ");
            input = scanner.nextLine().trim();
            if (!input.isEmpty()) timeoutSeconds = Long.parseLong(input);
        }
        
        // Create SSH Command Runner
        SSHExecutor runner = new SSHExecutor(new UserCredential(host, port, username, password,"",""));
        
        System.out.println("\n=== SSH Command Runner ===");
        System.out.println("Connected to: " + username + "@" + host + ":" + port);
        System.out.println("Command timeout: " + timeoutSeconds + " seconds");
        System.out.println("Priority: Exec channel (if successful), fallback to Shell channel");
        System.out.println("\nEnter commands to execute (type 'exit' to quit):");
        System.out.println("------------------------------------------------");
        
        Scanner commandScanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("\n> ");
            String command = commandScanner.nextLine().trim();
            
            if (command.equalsIgnoreCase("exit") || command.equalsIgnoreCase("quit")) {
                break;
            }
            
            if (command.isEmpty()) {
                continue;
            }
            
            // Execute command
            System.out.println("\nExecuting: " + command);
            System.out.println("...");
            
            try {
                long startTime = System.currentTimeMillis();
                CommandResult result = runner.executeCommand(command);
                long totalTime = System.currentTimeMillis() - startTime;
                
                System.out.println("\n=== Result ===");
                System.out.println("Channel Used: " + result.getChannelType());
                System.out.println("Success: " + result.isSuccess());
                System.out.println("Execution Time: " + result.getExecutionTimeMs() + "ms (Total: " + totalTime + "ms)");
                
                if (result.getOutput() != null && !result.getOutput().isEmpty()) {
                    System.out.println("\n--- Output ---");
                    System.out.println(result.getOutput());
                }
                
                if (result.getError() != null && !result.getError().isEmpty()) {
                    System.out.println("\n--- Error ---");
                    System.err.println(result.getError());
                }
                
            } catch (Exception e) {
                System.err.println("Error executing command: " + e.getMessage());
                logger.error("Command execution failed", e);
            }
        }
        
        // Cleanup
        runner.close();
        System.out.println("\nGoodbye!");
    }
}
