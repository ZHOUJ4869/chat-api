package com.jz.ai.utils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Random;

public class CaptchaUtil {

    private static final String CHAR_STRING = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 去掉易混淆字符

    public static String generateCode(int length) {
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < length; i++) {
            code.append(CHAR_STRING.charAt(random.nextInt(CHAR_STRING.length())));
        }
        return code.toString();
    }

    public static BufferedImage generateImage(String code) {
        int width = 100, height = 40;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        Random rand = new Random();

        // 背景
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        // 边框
        g.setColor(Color.GRAY);
        g.drawRect(0, 0, width - 1, height - 1);

        // 干扰线
        for (int i = 0; i < 10; i++) {
            g.setColor(new Color(rand.nextInt(255), rand.nextInt(255), rand.nextInt(255)));
            g.drawLine(rand.nextInt(width), rand.nextInt(height), rand.nextInt(width), rand.nextInt(height));
        }

        // 验证码字符
        g.setFont(new Font("Arial", Font.BOLD, 24));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(rand.nextInt(100), rand.nextInt(100), rand.nextInt(100)));
            g.drawString(String.valueOf(code.charAt(i)), 20 * i + 10, 30);
        }

        g.dispose();
        return image;
    }
}
