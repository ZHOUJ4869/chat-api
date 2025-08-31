package com.jz.ai.chat.lms;

public final class LmsRedisKeys {
    public static String list(String prefix, String chatId) {
        return prefix + chatId;                 // chat:lms:<chatId>
    }
    public static String stat(String prefix, String chatId) {
        return prefix + "stat:" + chatId;      // chat:lms:stat:<chatId>
    }
    public static String ewma(String prefix, String chatId) {
        return prefix + "ewma:" + chatId;      // chat:lms:ewma:<chatId>
    }
}
