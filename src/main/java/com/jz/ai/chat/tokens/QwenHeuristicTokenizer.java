package com.jz.ai.chat.tokens;

import java.util.regex.Pattern;

/** 经验估算：中文/日文/假名 1 char ~= 1 token；ASCII 4 chars ~= 1 token。*/
public class QwenHeuristicTokenizer implements Tokenizer {
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{InHiragana}\\p{InKatakana}]");

    @Override
    public int countText(String s) {
        if (s == null || s.isEmpty()) return 0;
        int cjk=0, ascii=0;
        for (int i=0;i<s.length();i++){
            char ch = s.charAt(i);
            if (CJK.matcher(String.valueOf(ch)).find()) cjk++;
            else ascii++;
        }
        int tkCjk = cjk;             // 1:1
        int tkAscii = Math.max(1, ascii / 4); // 4:1
        return tkCjk + tkAscii;
    }
}
