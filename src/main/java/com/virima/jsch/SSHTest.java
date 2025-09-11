//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.virima.jsch;

import com.jcraft.jsch.Session;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.security.Security;
import java.util.Scanner;

public class SSHTest {
    public static void main(String[] var0) {
        Security.addProvider(new BouncyCastleProvider());
        Scanner var1 = new Scanner(System.in);
        String var2 = "";
        String var3 = "";
        String var4 = "";
        System.out.println("Enter host : ");
        String var5 = var1.nextLine();
        System.out.println("Enter Username : ");
        String var6 = var1.nextLine();
        System.out.println("please select any one authentication type \n 1.Basic authentication \n 2.Private Key authentication");
        String var7 = var1.nextLine();
        System.out.println("option:" + Integer.parseInt(var7));
        switch (Integer.parseInt(var7)) {
            case 1:
                System.out.println("Enter Password : ");
                var2 = var1.nextLine();
                break;
            case 2:
                System.out.println("Enter Private Key file path without double quotes : ");
                var3 = "";
                var3 = var1.nextLine();
                var3 = readFile(var3);
                System.out.println(" Enter Phassprase (Skip if not required)  : ");
                var4 = var1.nextLine();
                break;
            default:
                System.out.println("please enter option");
                System.exit(0);
        }

        System.out.println("Enter Command: ");
        String var8 = var1.nextLine();
        System.out.println("Command entered: " + var8);
        var1.close();
        System.out.println("Getting output for host : " + var5 + " for command:- " + var8);
        Session var9 = SSHExecutor.getSshSessionForHost(var5, var6, var2, var3, var4, "22");
        CommandResult var10 = SSHCommandRunner.executeCommand(var9, var8.toString(), false, 800);
        System.out.println("-----------------------------OUTPUT--------------------------------\n" + var10);

        try {
            var9.disconnect();
        } catch (Exception var12) {
            var12.printStackTrace();
        }

    }

    public static String readFile(String var0) {
        BufferedReader var1 = null;
        String var2 = "";

        try {
            String var3;
            try {
                for(var1 = new BufferedReader(new FileReader(var0)); (var3 = var1.readLine()) != null; var2 = var2 + var3 + "\n") {
                }
            } catch (IOException var12) {
                var12.printStackTrace();
            }
        } finally {
            try {
                if (var1 != null) {
                    var1.close();
                    BufferedReader var14 = null;
                }
            } catch (IOException var11) {
                var11.printStackTrace();
            }

        }

        return var2;
    }
}
