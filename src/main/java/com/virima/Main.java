package com.virima;

import com.jcraft.jsch.Session;
import com.virima.jsch.CommandResult;
import com.virima.jsch.SSHCommandRunner;
import com.virima.jsch.SSHExecutor;

public class Main {
    public static void main(String[] args) {
        Session var9 = SSHExecutor.getSshSessionForHost("192.168.10.15", "hpsmon", "C1sc0!@#", "", "", "22");
        CommandResult var10 = SSHCommandRunner.executeCommand(var9, "show version",false, false,"C1sc0!@#", 800);
        System.out.println("-----------------------------OUTPUT--------------------------------\n" + var10);

        try {
            var9.disconnect();
        } catch (Exception var12) {
            var12.printStackTrace();
        }    }
}