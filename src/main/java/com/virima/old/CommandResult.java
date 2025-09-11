//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.virima.old;

public class CommandResult {
    boolean result;
    int exitCode;
    String output;

    public CommandResult(boolean result, int exitCode, String output) {
        this.result = result;
        this.exitCode = exitCode;
        this.output = output;
    }

    public String toString() {
        return this.output;
    }

    public boolean getResult() {
        return this.result;
    }

    public void setResult(boolean result) {
        this.result = result;
    }

    public int getExitCode() {
        return this.exitCode;
    }

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public String getOutput() {
        return this.output;
    }

    public void setOutput(String output) {
        this.output = output;
    }
}
