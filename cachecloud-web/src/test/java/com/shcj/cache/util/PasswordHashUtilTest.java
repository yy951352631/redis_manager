package com.shcj.cache.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHashUtilTest {

    @Test
    void newPasswordsUseSaltedBcrypt() {
        String first = PasswordHashUtil.encode("复杂密码-2026");
        String second = PasswordHashUtil.encode("复杂密码-2026");

        assertTrue(PasswordHashUtil.matches(first, "复杂密码-2026"));
        assertFalse(PasswordHashUtil.matches(first, "wrong"));
        assertFalse(PasswordHashUtil.needsUpgrade(first));
        assertNotEquals(first, second);
    }

    @Test
    void legacyMd5PasswordsRemainValidForMigration() {
        String asciiPassword = "legacy-password";
        String unicodePassword = "旧密码";

        assertTrue(PasswordHashUtil.matches(MD5Util.string2MD5(asciiPassword), asciiPassword));
        assertTrue(PasswordHashUtil.matches(MD5Util.legacyString2MD5(unicodePassword), unicodePassword));
        assertTrue(PasswordHashUtil.needsUpgrade(MD5Util.string2MD5(asciiPassword)));
    }

    @Test
    void seededAdminBcryptHashMatchesDocumentedPassword() {
        assertTrue(PasswordHashUtil.matches(
                "$2y$12$TG8IrOPabHn6z1EcAyJsuerXMzz/LcbNC7..pUOqc/afKXpAm08BK",
                "admin%TGB7ygv"));
    }
}
