package com.virima.tes;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.channel.ChannelShell;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

public class CiscoShellDemo {
    public static void main(String[] args) throws Exception {
        String host = "192.168.10.14";
        String user = "admin";
        String password = "HPSin@)(*&";
        int port = 22;

        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        try (ClientSession session = client.connect(user, host, port).verify(10, TimeUnit.SECONDS).getSession()) {
            session.addPasswordIdentity(password);
            session.auth().verify(5, TimeUnit.SECONDS);

            try (ChannelShell shell = session.createShellChannel()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                PipedOutputStream pipedIn = new PipedOutputStream();
                PipedInputStream pipedInput = new PipedInputStream(pipedIn);

                shell.setIn(pipedInput);
                shell.setOut(output);  // Capture both stdout and stderr
                shell.setErr(output);

                shell.open().verify(5, TimeUnit.SECONDS);

                // Send command (remember \n)
                String command = "some_invalid_command\n";
                pipedIn.write(command.getBytes(StandardCharsets.UTF_8));
                pipedIn.flush();

                // Optionally, send "exit\n" to close shell after command
                pipedIn.write("exit\n".getBytes(StandardCharsets.UTF_8));
                pipedIn.flush();

                // Wait for shell to finish
                shell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 10_000);

                // Read output
                String result = output.toString("UTF-8").trim();
                System.out.println("SHELL OUTPUT:");
                System.out.println(result);

                // Look for Cisco error in result (example)
                if (result.contains("Invalid input")) {
                    System.out.println("Caught Cisco CLI error!");
                }
            }
        } finally {
            client.stop();
        }
    }
}
