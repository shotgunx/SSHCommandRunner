package com.virima.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class NetworkDiagnostic {
    
    public static void main(String[] args) {
        String targetHost = "192.168.40.63";
        int sshPort = 22;
        
        System.out.println("=== Network Diagnostic for " + targetHost + " ===\n");
        
        // Test 1: Check if host is reachable via ICMP
        testPing(targetHost);
        
        // Test 2: Check if SSH port is open
        testPortConnection(targetHost, sshPort);
        
        // Test 3: Try alternative hosts on the network
        System.out.println("\n3. Scanning for other SSH servers on the network:");
        scanNetworkForSSH();
    }
    
    private static void testPing(String host) {
        System.out.println("1. Testing ICMP ping to " + host + ":");
        try {
            InetAddress address = InetAddress.getByName(host);
            boolean reachable = address.isReachable(5000); // 5 second timeout
            
            if (reachable) {
                System.out.println("   ✓ Host is reachable via ICMP");
            } else {
                System.out.println("   ✗ Host is NOT reachable via ICMP");
                System.out.println("   Possible causes:");
                System.out.println("   - Host is down");
                System.out.println("   - Firewall blocking ICMP");
                System.out.println("   - Host on different network segment");
            }
        } catch (IOException e) {
            System.out.println("   ✗ Error: " + e.getMessage());
        }
    }
    
    private static void testPortConnection(String host, int port) {
        System.out.println("\n2. Testing TCP connection to " + host + ":" + port + ":");
        
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000); // 5 second timeout
            System.out.println("   ✓ SSH port " + port + " is OPEN");
            System.out.println("   The host is up and SSH service is running!");
        } catch (IOException e) {
            System.out.println("   ✗ Cannot connect to SSH port " + port);
            System.out.println("   Error: " + e.getMessage());
            
            if (e.getMessage().contains("No route to host")) {
                System.out.println("\n   DIAGNOSIS: Network routing issue");
                System.out.println("   - The host is on the same subnet but not responding to ARP");
                System.out.println("   - Most likely the host is powered off or disconnected");
            } else if (e.getMessage().contains("Connection refused")) {
                System.out.println("\n   DIAGNOSIS: Host is up but SSH service is not running");
                System.out.println("   - Check if SSH service is started on the target host");
            } else if (e.getMessage().contains("timeout")) {
                System.out.println("\n   DIAGNOSIS: Connection timeout");
                System.out.println("   - Firewall might be dropping packets");
                System.out.println("   - Host might be on a different VLAN");
            }
        }
    }
    
    private static void scanNetworkForSSH() {
        String subnet = "192.168.40.";
        int[] commonSSHHosts = {1, 10, 99, 136, 142, 149, 151, 153, 155, 158, 159, 164, 179, 202, 216, 230, 245};
        
        System.out.println("   Checking known hosts on the network for SSH...");
        
        for (int lastOctet : commonSSHHosts) {
            String host = subnet + lastOctet;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, 22), 100); // 100ms timeout for quick scan
                System.out.println("   ✓ Found SSH server at: " + host);
            } catch (IOException e) {
                // Host doesn't have SSH, skip
            }
        }
        
        System.out.println("\n   Tip: Try using one of the working SSH servers above to test your code");
    }
}
