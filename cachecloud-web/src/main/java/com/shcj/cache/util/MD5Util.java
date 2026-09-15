package com.shcj.cache.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * @Author: zengyizhao
 * @DateTime: 2022/1/26 12:05
 * @Description:
 */
public class MD5Util {

    /**
     * @Description: MD5加码 生成32位md5码
     * @Author: caoru
     * @CreateDate: 2018/8/9 14:21
     */
    public static String string2MD5(String inStr) {
        MessageDigest md5 = null;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            System.out.println(e.toString());
            e.printStackTrace();
            return "";
        }
        byte[] md5Bytes = md5.digest(inStr.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexValue = new StringBuilder();
        for (byte md5Byte : md5Bytes) {
            int val = ((int) md5Byte) & 0xff;
            if (val < 16) {
                hexValue.append("0");
            }
            hexValue.append(Integer.toHexString(val));
        }
        return hexValue.toString();

    }

    /**
     * 兼容历史密码哈希。旧实现会截断非 ASCII 字符，只用于登录后迁移，禁止新数据使用。
     */
    static String legacyString2MD5(String inStr) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            char[] chars = inStr.toCharArray();
            byte[] bytes = new byte[chars.length];
            for (int i = 0; i < chars.length; i++) {
                bytes[i] = (byte) chars[i];
            }
            return toHex(md5.digest(bytes));
        } catch (Exception e) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hexValue = new StringBuilder();
        for (byte value : bytes) {
            int unsignedValue = value & 0xff;
            if (unsignedValue < 16) {
                hexValue.append('0');
            }
            hexValue.append(Integer.toHexString(unsignedValue));
        }
        return hexValue.toString();
    }

}
