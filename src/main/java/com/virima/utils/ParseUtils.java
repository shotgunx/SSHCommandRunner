package com.virima.utils;

import java.io.ByteArrayOutputStream;

public class ParseUtils {
    public static String detectPromptSmartly(ByteArrayOutputStream output) {
        try {
            String previousOutput = "";
            String currentOutput = "";
            int stableCount = 0;
            int maxWaitTime = 90000;
            int waitInterval = 500;

            for(int waited = 0; waited < maxWaitTime; waited += waitInterval) {
                Thread.sleep((long)waitInterval);
                currentOutput = output.toString("UTF-8");
                if (currentOutput.equals(previousOutput)) {
                    ++stableCount;
                    if (stableCount >= 3) {
                        break;
                    }
                } else {
                    stableCount = 0;
                }

                previousOutput = currentOutput;
            }

            String[] lines = currentOutput.split("\\r?\\n");
            String detectedPrompt = "";
            String[] commonPrompts = new String[]{"\\$\\s*$", "#\\s*$", ">\\s*$", ":\\s*$", "\\]\\s*$", "\\)\\s*$"};

            for(int i = lines.length - 1; i >= 0; --i) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    for(String pattern : commonPrompts) {
                        if (line.matches(".*" + pattern)) {
                            return line;
                        }
                    }
                }
            }

            for(int i = lines.length - 1; i >= 0; --i) {
                String line = lines[i].trim();
                if (!line.isEmpty() && isLikelyPrompt(line)) {
                    return line;
                }
            }

            for(int i = lines.length - 1; i >= 0; --i) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    return line;
                }
            }

            return "";
        } catch (Exception var15) {
            String output_str = output.toString();
            String[] lines = output_str.split("\\r?\\n");

            for(int i = lines.length - 1; i >= 0; --i) {
                if (!lines[i].trim().isEmpty()) {
                    return lines[i].trim();
                }
            }

            return "";
        }
    }

    private static boolean isLikelyPrompt(String line) {
        if (line.length() > 200) {
            return false;
        } else if (line.matches("^[^a-zA-Z0-9]*$") && line.length() > 10) {
            return false;
        } else {
            boolean hasUser = line.contains("@") || line.matches(".*[a-zA-Z0-9]+.*");
            boolean hasPath = line.contains("/") || line.contains("\\") || line.contains("~");
            boolean hasPromptChar = line.matches(".*[#$>:]\\s*$");
            boolean hasHostname = line.matches(".*[a-zA-Z0-9-]+@[a-zA-Z0-9-]+.*");
            int score = 0;
            if (hasUser) {
                score += 2;
            }

            if (hasPath) {
                score += 2;
            }

            if (hasPromptChar) {
                score += 3;
            }

            if (hasHostname) {
                score += 2;
            }

            if (line.length() < 100) {
                ++score;
            }

            if (line.matches(".*\\[[a-zA-Z0-9@/~\\-\\s]+\\].*")) {
                score += 2;
            }

            return score >= 3;
        }
    }

}
