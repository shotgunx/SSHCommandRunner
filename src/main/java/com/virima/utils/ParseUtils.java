package com.virima.utils;

import com.virima.smartRunner.UserCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

public class ParseUtils {
    private static final Logger logger = LoggerFactory.getLogger(ParseUtils.class);

    public static String detectPromptSmartly(ByteArrayOutputStream output, PipedOutputStream pipedIn, UserCredential userCredential, boolean allow) {
        try {
            String previousOutput = "";
            String currentOutput = "";
            int stableCount = 0;
            int maxWaitTime = 90000;
            int waitInterval = 5000;

            boolean firstDone=false;
            while(true) {
                for (int waited = 0; waited < maxWaitTime; waited += waitInterval) {
                    Thread.sleep((long) waitInterval);
                    currentOutput = output.toString("UTF-8");
                    System.out.println(currentOutput);
                    if (currentOutput.equals(previousOutput)) {
                        ++stableCount;
                        if (stableCount >= 3&&firstDone) {
                            break;
                        }else if(stableCount >= 3&&!firstDone){
                            pipedIn.write("\n".getBytes(StandardCharsets.UTF_8));
                            pipedIn.flush();
                            stableCount=0;
                            firstDone=true;
                            try{Thread.sleep(1000);} catch (Exception e) {

                            }
                        }
                    } else {
                        stableCount = 0;
                    }

                    previousOutput = currentOutput;
                }

                logger.info("[DETECT-PROMPT-SMARTLY] stable output: '{}'", previousOutput);

                boolean handledLogin=InteractivePromptHandler.handleLoginPrompt(currentOutput,pipedIn,userCredential,allow);
                if(!handledLogin)
                    break;

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
