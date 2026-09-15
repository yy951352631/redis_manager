package com.shcj.cache.util;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码哈希入口。新密码统一使用 BCrypt，MD5 仅用于平滑迁移已有账户。
 */
public final class PasswordHashUtil {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

    private PasswordHashUtil() {
    }

    public static String encode(String rawPassword) {
        if (StringUtils.isBlank(rawPassword)) {
            throw new IllegalArgumentException("password must not be blank");
        }
        return BCRYPT.encode(rawPassword);
    }

    public static boolean matches(String storedPassword, String rawPassword) {
        if (StringUtils.isBlank(storedPassword) || rawPassword == null) {
            return false;
        }
        if (isBcrypt(storedPassword)) {
            return BCRYPT.matches(rawPassword, storedPassword);
        }
        return storedPassword.equalsIgnoreCase(MD5Util.string2MD5(rawPassword))
                || storedPassword.equalsIgnoreCase(MD5Util.legacyString2MD5(rawPassword));
    }

    public static boolean needsUpgrade(String storedPassword) {
        return StringUtils.isNotBlank(storedPassword) && !isBcrypt(storedPassword);
    }

    private static boolean isBcrypt(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }
}
