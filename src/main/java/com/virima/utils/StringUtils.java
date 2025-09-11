

package com.virima.utils;

public class StringUtils {
    public static int countSubstring(String text, String sub) {
        if (sub.isEmpty()) {
            return 0;
        } else {
            int count = 0;

            int idx;
            for(idx = 0; (idx = text.indexOf(sub, idx)) != -1; idx += sub.length()) {
                ++count;
            }

            return count;
        }
    }
}
