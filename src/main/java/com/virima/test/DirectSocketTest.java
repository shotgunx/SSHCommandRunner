package com.virima.test;

import java.io.IOException;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

public class DirectSocketTest {
    
    public static void main(String[] args) {
        String host = "192.168.40.63";
        int port = 22;
        
        System.out.println("=== Testing Different Connection Methods to " + host + ":" + port + " ===\n");
        
        // Test 1: Traditional Socket
        testTraditionalSocket(host, port);
        
        // Test 2: Socket with explicit bind
        testSocketWithBind(host, port);
        
        // Test 3: NIO SocketChannel
        testNIOSocketChannel(host, port);
        
        // Test 4: Test with system properties
        testWithSystemProperties(host, port);
        
        // Test 5: Test localhost as control
        System.out.println("\n5. Control Test - Localhost SSH:");
        testTraditionalSocket("localhost", 22);
    }
    
    private static void testTraditionalSocket(String host, int port) {
        System.out.println("1. Testing Traditional Socket Connection:");
        try {
            Socket socket = new Socket();
            socket.setSoTimeout(5000);
            
            System.out.println("   Attempting to connect to " + host + ":" + port);
            long startTime = System.currentTimeMillis();
            
            socket.connect(new InetSocketAddress(host, port), 5000);
            
            long connectTime = System.currentTimeMillis() - startTime;
            System.out.println("   ✓ SUCCESS! Connected in " + connectTime + "ms");
            System.out.println("   Local address: " + socket.getLocalAddress() + ":" + socket.getLocalPort());
            System.out.println("   Remote address: " + socket.getRemoteSocketAddress());
            
            socket.close();
        } catch (IOException e) {
            System.out.println("   ✗ FAILED: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            
            // Additional diagnostic
            if (e instanceof NoRouteToHostException) {
                System.out.println("   Diagnosis: Operating system cannot find route to host");
                System.out.println("   - Check: sudo pfctl -d (to temporarily disable pf firewall)");
                System.out.println("   - Check: System Preferences > Security & Privacy > Firewall");
            }
        }
    }
    
    private static void testSocketWithBind(String host, int port) {
        System.out.println("\n2. Testing Socket with Explicit Bind to en0 Interface:");
        try {
            Socket socket = new Socket();
            socket.setSoTimeout(5000);
            
            // Explicitly bind to the en0 interface IP
            InetAddress localAddr = InetAddress.getByName("192.168.40.190");
            socket.bind(new InetSocketAddress(localAddr, 0));
            
            System.out.println("   Bound to: " + socket.getLocalSocketAddress());
            System.out.println("   Attempting to connect to " + host + ":" + port);
            
            socket.connect(new InetSocketAddress(host, port), 5000);
            
            System.out.println("   ✓ SUCCESS! Connected");
            System.out.println("   Remote: " + socket.getRemoteSocketAddress());
            
            socket.close();
        } catch (IOException e) {
            System.out.println("   ✗ FAILED: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
    
    private static void testNIOSocketChannel(String host, int port) {
        System.out.println("\n3. Testing NIO SocketChannel:");
        try {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            
            System.out.println("   Attempting to connect to " + host + ":" + port);
            boolean connected = channel.connect(new InetSocketAddress(host, port));
            
            if (!connected) {
                // Wait for connection to complete
                long timeout = System.currentTimeMillis() + 5000;
                while (!channel.finishConnect()) {
                    if (System.currentTimeMillis() > timeout) {
                        throw new SocketTimeoutException("Connection timeout");
                    }
                    Thread.sleep(50);
                }
            }
            
            System.out.println("   ✓ SUCCESS! Connected via NIO");
            System.out.println("   Local: " + channel.getLocalAddress());
            System.out.println("   Remote: " + channel.getRemoteAddress());
            
            channel.close();
        } catch (Exception e) {
            System.out.println("   ✗ FAILED: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
    
    private static void testWithSystemProperties(String host, int port) {
        System.out.println("\n4. Testing with Modified System Properties:");
        
        // Set system properties that might affect networking
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.useSystemProxies", "false");
        
        System.out.println("   Set java.net.preferIPv4Stack=true");
        System.out.println("   Set java.net.useSystemProxies=false");
        
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            
            System.out.println("   ✓ SUCCESS! Connected with modified properties");
            socket.close();
        } catch (IOException e) {
            System.out.println("   ✗ FAILED: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}
