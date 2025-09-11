package com.virima.tes;

import com.virima.utils.AnsiStrippingInputStream;

import java.io.InputStream;

public class Test1 {

    public static void main(String[] args) throws Exception {
        String demo = "\u001B[32mOK\u001B[0m \rPROG 10%\rPROG 100%\nHello\b!\n";
        byte[] bytes = demo.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (InputStream in = new java.io.ByteArrayInputStream(bytes);
             InputStream clean = new AnsiStrippingInputStream(in);
             java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(clean))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        }
        // Output:
        // PROG 100%
        // Hell!
    }
}
