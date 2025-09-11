package com.virima.smartRunner;

import java.util.Objects;

public final class UserCredential {
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String privateKeyFilePath;
    private final String passPhrase;

    public UserCredential(String host, int port, String username, String password, String privateKeyFilePath, String passPhrase) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyFilePath = privateKeyFilePath;
        this.passPhrase = passPhrase;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String privateKeyFilePath() {
        return privateKeyFilePath;
    }

    public String passPhrase() {
        return passPhrase;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        UserCredential that = (UserCredential) obj;
        return Objects.equals(this.host, that.host) &&
                this.port == that.port &&
                Objects.equals(this.username, that.username) &&
                Objects.equals(this.password, that.password) &&
                Objects.equals(this.privateKeyFilePath, that.privateKeyFilePath) &&
                Objects.equals(this.passPhrase, that.passPhrase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, username, password, privateKeyFilePath, passPhrase);
    }

    @Override
    public String toString() {
        return "UserCredential[" +
                "host=" + host + ", " +
                "port=" + port + ", " +
                "username=" + username + ", " +
                "password=" + password + ", " +
                "privateKeyFilePath=" + privateKeyFilePath + ", " +
                "passPhrase=" + passPhrase + ']';
    }

}
