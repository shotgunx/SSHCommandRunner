package com.virima.utils;

public class StringUtils {
    public static int countSubstring(String text, String sub) {
        if (sub.isEmpty()) return 0; // Avoid infinite loop!
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length(); // Move past this occurrence
        }
        return count;
    }
}
