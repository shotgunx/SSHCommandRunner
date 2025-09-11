//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.virima.jsch;

import com.jcraft.jsch.Session;

import java.util.concurrent.*;

public class SSHCommandRunner implements Callable<CommandResult> {
    private static final String CLASSNAME = "SSHCommandRunner";
    Session session;
    String command;
    boolean isAdmin;

    public SSHCommandRunner(Session session, String command, boolean isAdmin) {
        this.session = session;
        this.command = command;
        this.isAdmin = isAdmin;
    }

    public static CommandResult executeCommand(Session session, String command, boolean isAdmin, int sshExecutionTimeInSeconds) {
        CommandResult commandResult = new CommandResult(false, -1, "");
        long startTime = System.currentTimeMillis();
        System.out.println("SSHCommandRunnerExecuting shell script on host " + session.getHost()+ " with a timeout of " + sshExecutionTimeInSeconds + " secs");
        SSHCommandRunner sshCommandRunner = new SSHCommandRunner(session, command, isAdmin);
        ExecutorService service = Executors.newSingleThreadExecutor();

        CommandResult var111;
        try {
            Future<CommandResult> future = service.submit(sshCommandRunner);
            commandResult = (CommandResult)future.get((long)sshExecutionTimeInSeconds, TimeUnit.SECONDS);
            System.out.println("SSHCommandRunnerShell script execution completed on host " + session.getHost() + " took " + (System.currentTimeMillis() - startTime) + " ms");
            var111 = commandResult;
            return var111;
        } catch (TimeoutException var16) {
            System.out.println("SSHCommandRunnerCould not execute Shell script within " + sshExecutionTimeInSeconds + " secs on host " + session.getHost());
            var111 = commandResult;
        } catch (Exception var17) {
            CommandResult var11 = commandResult;
            return var11;
        } finally {
            service.shutdown();
        }

        return var111;
    }

    public CommandResult call() throws Exception {
        CommandResult commandResult = null;

        try {
            commandResult = SSHExecutor.newExecuteCommand(this.session, this.command, this.isAdmin);
            return commandResult;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
