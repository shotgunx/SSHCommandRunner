//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.virima.sshd;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.sshd.client.session.ClientSession;

public class SSHCommandRunner implements Callable<CommandResult> {
    private static final String CLASSNAME = "SSHCommandRunner";
    ClientSession session;
    String command;
    boolean isAdmin;

    public SSHCommandRunner(ClientSession session, String command, boolean isAdmin) {
        this.session = session;
        this.command = command;
        this.isAdmin = isAdmin;
    }

    public static CommandResult executeCommand(ClientSession session, String command, boolean isAdmin, int sshExecutionTimeInSeconds) {
        CommandResult commandResult = new CommandResult(false, -1, "");
        long startTime = System.currentTimeMillis();
        System.out.println("SSHCommandRunnerExecuting shell script on host " + session.getRemoteAddress() + " with a timeout of " + sshExecutionTimeInSeconds + " secs");
        SSHCommandRunner sshCommandRunner = new SSHCommandRunner(session, command, isAdmin);
        ExecutorService service = Executors.newSingleThreadExecutor();

        CommandResult var111;
        try {
            Future<CommandResult> future = service.submit(sshCommandRunner);
            commandResult = (CommandResult)future.get((long)sshExecutionTimeInSeconds, TimeUnit.SECONDS);
            System.out.println("SSHCommandRunnerShell script execution completed on host " + session.getRemoteAddress() + " took " + (System.currentTimeMillis() - startTime) + " ms");
            var111 = commandResult;
            return var111;
        } catch (TimeoutException var16) {
            System.out.println("SSHCommandRunnerCould not execute Shell script within " + sshExecutionTimeInSeconds + " secs on host " + session.getRemoteAddress());
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
