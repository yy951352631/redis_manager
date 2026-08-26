package com.shcj.cache.util;

import org.apache.commons.lang.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 从 INFO server 的 {@code os} 字段解析 CPU 架构。
 *
 * <p>字段形如 {@code Linux 5.14.0-687.el9.x86_64 x86_64} 或 {@code Linux 6.8.0-117-generic aarch64}：
 * 内核版本里也可能出现架构串（如 {@code .el9.x86_64}），所以只取最后一段——那是 {@code uname -m} 的输出，
 * 才是真正的机器架构。
 */
public final class RedisOsArchUtil {

    public static final String ARCH_X86 = "X86";
    public static final String ARCH_ARM = "ARM";

    private RedisOsArchUtil() {
    }

    /**
     * @param infoMap RedisCenter 解析出的分段 INFO，键为段名
     * @return X86 / ARM / uname -m 原文；无法判定时返回 null
     */
    public static String resolveArch(Map<String, Object> infoMap) {
        return normalize(extractMachine(readOsLine(infoMap)));
    }

    /** 从分段 INFO 中取出 os 行，兼容 Server / server 两种段名写法 */
    public static String readOsLine(Map<String, Object> infoMap) {
        if (infoMap == null) {
            return null;
        }
        Object server = infoMap.get("Server");
        if (!(server instanceof Map)) {
            server = infoMap.get("server");
        }
        if (!(server instanceof Map)) {
            return null;
        }
        Object os = ((Map<?, ?>) server).get("os");
        return os == null ? null : String.valueOf(os);
    }

    /** 取 os 行的最后一段，即 uname -m */
    static String extractMachine(String osLine) {
        if (StringUtils.isBlank(osLine)) {
            return null;
        }
        String[] parts = osLine.trim().split("\\s+");
        return parts.length == 0 ? null : parts[parts.length - 1];
    }

    /**
     * uname -m 归一化。未知架构返回原文而不是「未知」——真出现新架构时，
     * 页面上直接看到 {@code riscv64} 比看到「未知」有用得多。
     */
    static String normalize(String machine) {
        if (StringUtils.isBlank(machine)) {
            return null;
        }
        String value = machine.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("aarch") || value.startsWith("arm")) {
            return ARCH_ARM;
        }
        if (value.equals("x86_64") || value.equals("amd64") || value.equals("x64")
                || value.equals("x86") || value.matches("i[3-6]86")) {
            return ARCH_X86;
        }
        return machine.trim();
    }
}
