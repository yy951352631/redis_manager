package com.shcj.cache.offline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「把 AOF 当 RDB 上传」的识别测试。
 *
 * <p>Redis 4.0 起 aof-use-rdb-preamble 默认开启，此时 appendonly.aof 是
 * 「RDB 快照 + RESP 命令流」的拼接，开头同样是 REDIS 魔数，能通过上传时的
 * 文件头校验，直到解析器撞上命令流才抛出难以理解的底层异常。
 *
 * <p>下面的字节形态取自真实文件：
 * <ul>
 *   <li>RDB 结尾：{@code ... 69 ff 12 48 80 22 e7 70 1a 8c}（0xFF + 8 字节 CRC64）</li>
 *   <li>AOF 结尾：{@code ...\r\n$13\r\n1787730380765\r\n}</li>
 * </ul>
 */
public class OfflineAofDetectionTest {

    private final OfflineAnalysisService service = new OfflineAnalysisService();

    @TempDir
    Path tempDir;

    private File write(String name, byte[] content) throws IOException {
        File f = tempDir.resolve(name).toFile();
        Files.write(f.toPath(), content);
        return f;
    }

    /** 构造「RDB 前导 + RESP 命令流」，即带 rdb-preamble 的 AOF */
    private byte[] aofWithRdbPreamble() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("REDIS0009".getBytes(StandardCharsets.ISO_8859_1));
        out.write(new byte[]{(byte) 0xFA, 0x09, 'r', 'e', 'd', 'i', 's'});
        // RDB 段的结束标记与校验和
        out.write(new byte[]{(byte) 0xFF, 0x12, 0x48, (byte) 0x80, 0x22, (byte) 0xE7, 0x70, 0x1A, (byte) 0x8C});
        // 其后是 AOF 的命令流
        out.write(("*3\r\n$9\r\nPEXPIREAT\r\n$20\r\ncounter:pay:20260824\r\n"
                + "$13\r\n1787730380765\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    @Test
    public void 带rdb前导的aof应被识别() throws IOException {
        File aof = write("appendonly-renamed.rdb", aofWithRdbPreamble());
        assertTrue(service.looksLikeAofWithRdbPreamble(aof),
                "AOF 以 RESP 命令流结尾，应能被识别出来");
    }

    @Test
    public void 真实rdb不应被误判() {
        java.net.URL url = getClass().getClassLoader().getResource("offline/sample-dump.rdb");
        Assumptions.assumeTrue(url != null, "缺少样例 RDB 文件");
        File rdb = new File(url.getFile());
        assertFalse(service.looksLikeAofWithRdbPreamble(rdb),
                "正常 RDB 以 0xFF + CRC64 二进制结尾，不能被判成 AOF");
    }

    @Test
    public void 空文件与过短文件不应崩溃() throws IOException {
        assertFalse(service.looksLikeAofWithRdbPreamble(write("empty.rdb", new byte[0])));
        assertFalse(service.looksLikeAofWithRdbPreamble(write("tiny.rdb", new byte[]{'R', 'E'})));
        assertFalse(service.looksLikeAofWithRdbPreamble(new File(tempDir.toFile(), "nope.rdb")));
        assertFalse(service.looksLikeAofWithRdbPreamble(null));
    }

    @Test
    public void 仅以CRLF结尾但无RESP前缀的不算aof() throws IOException {
        // 校验和末两字节恰好是 0x0D0A 的极小概率情形，不应误判
        byte[] content = new byte[64];
        content[62] = '\r';
        content[63] = '\n';
        assertFalse(service.looksLikeAofWithRdbPreamble(write("coincidence.rdb", content)),
                "尾部没有 RESP 长度前缀时不应判为 AOF");
    }
}
