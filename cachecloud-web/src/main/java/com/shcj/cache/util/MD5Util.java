package com.shcj.cache.util;

import org.apache.commons.lang3.StringUtils;

import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * @Author: zengyizhao
 * @DateTime: 2022/1/26 12:05
 * @Description:
 */
public class MD5Util {

    private static final Pattern MD5_HEX = Pattern.compile("^[0-9a-fA-F]{32}$");

    /**
     * 登录密码入库：明文转 MD5；已是 32 位十六进制则视为密文原样保存（避免二次哈希）。
     */
    public static String toStorePassword(String password) {
        if (StringUtils.isBlank(password)) {
            return password;
        }
        if (MD5_HEX.matcher(password).matches()) {
            return password.toLowerCase();
        }
        return string2MD5(password);
    }

    /**
     * 校验登录密码：库中 MD5 与 MD5(输入) 比对。
     */
    public static boolean matchesStoredPassword(String stored, String inputPlain) {
        if (StringUtils.isBlank(stored) || inputPlain == null) {
            return false;
        }
        return stored.equalsIgnoreCase(string2MD5(inputPlain));
    }

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
        char[] charArray = inStr.toCharArray();
        byte[] byteArray = new byte[charArray.length];

        for (int i = 0; i < charArray.length; i++) {
            byteArray[i] = (byte) charArray[i];
        }
        byte[] md5Bytes = md5.digest(byteArray);
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
     * @Description: 加密解密算法 执行一次加密，两次解密
     * @Author: caoru
     * @CreateDate: 2018/8/9 14:21
     */
    public static String convertMD5(String inStr) {

        char[] a = inStr.toCharArray();
        for (int i = 0; i < a.length; i++) {
            a[i] = (char) (a[i] ^ 't');
        }
        return new String(a);

    }


    public static void main(String[] args) {
        String s = new String("admin");
        String encryptStr = convertMD5(s);
        System.out.println("原始：" + s);
        System.out.println("MD5后：" + string2MD5(s));
        System.out.println("加密的：" + encryptStr);
        System.out.println("解密的：" + convertMD5(encryptStr));
    }
}
