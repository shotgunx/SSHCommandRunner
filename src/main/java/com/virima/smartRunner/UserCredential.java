package com.virima.smartRunner;

public record UserCredential(String host, int port, String username, String password, String privateKeyFilePath, String passPhrase) {
}
