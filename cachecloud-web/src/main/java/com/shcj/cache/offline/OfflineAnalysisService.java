package com.shcj.cache.offline;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.dao.OfflineAnalysisRecordDao;
import com.shcj.cache.entity.OfflineAnalysisRecord;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisStatsSnapshotDto;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 离线数据分析：上传 RDB 文件，异步解析成与在线键值分析同构的结果。
 *
 * <p>解析串行执行——一次解析会把整份文件的分桶结构常驻内存，多份并行时内存不可控，
 * 而离线分析本身不是高频操作，排队等待比 OOM 好。
 */
@Service
public class OfflineAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(OfflineAnalysisService.class);

    /** RDB 文件头固定以此开头，用来在解析前快速拒绝非 RDB 文件 */
    private static final byte[] RDB_MAGIC = {'R', 'E', 'D', 'I', 'S'};

    /** 判定 AOF 时读取的文件尾部字节数 */
    private static final int AOF_TAIL_PROBE_BYTES = 512;

    /** 小于该长度的文件不足以做尾部判定 */
    private static final int AOF_TAIL_MIN_BYTES = 16;

    private static final long DEFAULT_BIG_KEY_STRING_BYTES = 100 * 1024L;
    private static final long DEFAULT_BIG_KEY_COLLECTION_ELEMENTS = 50000L;

    @Value("${cachecloud.offline.upload-dir:}")
    private String configuredUploadDir;

    @Autowired
    private OfflineAnalysisRecordDao offlineAnalysisRecordDao;

    @Autowired
    private RdbAnalysisParser rdbAnalysisParser;

    private ExecutorService executor;

    @PostConstruct
    public void initialize() {
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "offline-analysis");
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            // 上次运行留下的"待分析/分析中"记录对应的线程已经不在了，标记为失败而不是一直挂着
            int stale = offlineAnalysisRecordDao.markStaleAsFailed("服务重启，该次分析已中断，请重新上传");
            if (stale > 0) {
                logger.info("marked {} stale offline analysis records as failed", stale);
            }
        } catch (Exception e) {
            logger.warn("mark stale offline analysis failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private Path uploadDir() throws IOException {
        String dir = StringUtils.isNotBlank(configuredUploadDir)
                ? configuredUploadDir
                : System.getProperty("java.io.tmpdir") + File.separator + "cachecloud-offline";
        Path path = Paths.get(dir);
        Files.createDirectories(path);
        return path;
    }

    public OfflineAnalysisRecord upload(MultipartFile file, String userName) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要分析的文件");
        }
        String originalName = StringUtils.defaultString(file.getOriginalFilename(), "unknown");
        String lower = originalName.toLowerCase();
        if (lower.endsWith(".aof") || lower.contains("appendonly")) {
            throw new BizException("暂不支持 AOF：AOF 是命令流而非数据快照，需先重放才能得到最终数据集。"
                    + "请改用 BGSAVE 生成的 RDB 文件");
        }

        Path target;
        try {
            Path dir = uploadDir();
            // 落盘名带时间戳，避免同名文件互相覆盖
            target = dir.resolve(System.currentTimeMillis() + "-" + sanitize(originalName));
            try (java.io.InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException("文件保存失败：" + e.getMessage());
        }

        if (!looksLikeRdb(target.toFile())) {
            deleteQuietly(target.toFile());
            throw new BizException("文件不是有效的 RDB：缺少 REDIS 文件头。请确认上传的是 dump.rdb 而不是 AOF 或压缩包");
        }

        OfflineAnalysisRecord record = new OfflineAnalysisRecord();
        record.setFileName(originalName);
        record.setFileSize(file.getSize());
        record.setFilePath(target.toAbsolutePath().toString());
        record.setFileType("RDB");
        record.setStatus(OfflineAnalysisRecord.STATUS_PENDING);
        record.setProgress(0);
        record.setKeyCount(0);
        record.setUserName(StringUtils.defaultString(userName));
        record.setCreateTime(new Date());
        record.setUpdateTime(new Date());
        offlineAnalysisRecordDao.save(record);

        final long recordId = record.getId();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                analyze(recordId);
            }
        });
        return record;
    }

    private void analyze(long recordId) {
        OfflineAnalysisRecord record = offlineAnalysisRecordDao.getById(recordId);
        if (record == null || StringUtils.isBlank(record.getFilePath())) {
            return;
        }
        File file = new File(record.getFilePath());
        try {
            offlineAnalysisRecordDao.updateProgress(recordId, OfflineAnalysisRecord.STATUS_RUNNING, 0, 0);
            KeyAnalysisStatsSnapshotDto snapshot = rdbAnalysisParser.parse(file,
                    DEFAULT_BIG_KEY_STRING_BYTES, DEFAULT_BIG_KEY_COLLECTION_ELEMENTS,
                    keyCount -> offlineAnalysisRecordDao.updateProgress(
                            recordId, OfflineAnalysisRecord.STATUS_RUNNING, 0, keyCount));

            long totalKeys = 0;
            for (com.shcj.cache.web.controller.api.dto.ParamCountDto item : snapshot.getKeyTypeDistri()) {
                totalKeys += (long) item.getCount();
            }
            // RDB 里没有空闲时间信息，明确告知而不是让页面上出现一张空图
            snapshot.getAnalysisRisks().add("离线分析基于 RDB 文件，其中不包含 key 的空闲时间，因此没有空闲键分布");
            snapshot.getAnalysisRisks().add("体积为估算值（key 长度 + 元素长度 + 每元素固定开销），"
                    + "与在线分析的 MEMORY USAGE 存在差异，适合看分布与相对大小");

            offlineAnalysisRecordDao.finish(recordId, totalKeys, JSON.toJSONString(snapshot));
            logger.info("offline analysis done id={} keys={}", recordId, totalKeys);
        } catch (Exception e) {
            logger.error("offline analysis failed id=" + recordId, e);
            String message = StringUtils.isBlank(e.getMessage())
                    ? e.getClass().getSimpleName() : e.getMessage();
            message = appendLikelyCause(message, file);
            offlineAnalysisRecordDao.fail(recordId, StringUtils.abbreviate(message, 1000));
        } finally {
            // 解析完即删除源文件：RDB 里是全量业务数据，没有留在磁盘上的理由
            deleteQuietly(file);
        }
    }

    public List<OfflineAnalysisRecord> list(int pageNo, int pageSize) {
        int safePageNo = pageNo <= 0 ? 1 : pageNo;
        int safePageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        return offlineAnalysisRecordDao.list((safePageNo - 1) * safePageSize, safePageSize);
    }

    public int count() {
        return offlineAnalysisRecordDao.count();
    }

    public OfflineAnalysisRecord get(long id) {
        return offlineAnalysisRecordDao.getById(id);
    }

    public void delete(long id) {
        OfflineAnalysisRecord record = offlineAnalysisRecordDao.getById(id);
        if (record == null) {
            return;
        }
        if (record.getStatus() == OfflineAnalysisRecord.STATUS_RUNNING) {
            throw new BizException("该记录正在分析中，请等待分析结束后再删除");
        }
        if (StringUtils.isNotBlank(record.getFilePath())) {
            deleteQuietly(new File(record.getFilePath()));
        }
        offlineAnalysisRecordDao.deleteById(id);
    }

    /**
     * 解析失败时补一句最可能的原因，直接写进「失败原因」列。
     *
     * 最常见的坑是把 AOF 当 RDB 传上来。Redis 4.0 起 aof-use-rdb-preamble 默认开启，
     * 这种 AOF 是「RDB 快照 + RESP 命令流」的拼接，开头同样是 REDIS 魔数，
     * 因此能骗过上传时的文件头校验，直到解析器读完前半段撞上命令流才报错，
     * 而那时抛出的是难以理解的底层异常。
     */
    private String appendLikelyCause(String message, File file) {
        if (!looksLikeAofWithRdbPreamble(file)) {
            return message;
        }
        return message + "。该文件以 RDB 快照开头、后接 AOF 命令流，很可能是开启了 "
                + "aof-use-rdb-preamble 的 AOF 文件（appendonly.aof）。"
                + "AOF 是命令流而非数据快照，需先重放才能得到最终数据集，"
                + "请改用 BGSAVE 生成的 RDB 文件";
    }

    /**
     * 判断文件是否为「带 RDB 前导的 AOF」。
     *
     * 依据文件尾部：完整 RDB 以 0xFF 加 8 字节 CRC64 校验和结尾，是二进制；
     * 而 AOF 的命令流以 RESP 文本结尾，形如 ...\r\n$13\r\n1787730306570\r\n。
     * 只读尾部若干字节，不受文件体积影响。
     */
    // 包级可见，便于单元测试直接覆盖尾部判定逻辑
    boolean looksLikeAofWithRdbPreamble(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        long length = file.length();
        if (length < AOF_TAIL_MIN_BYTES) {
            return false;
        }
        byte[] tail = new byte[(int) Math.min(AOF_TAIL_PROBE_BYTES, length)];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(length - tail.length);
            raf.readFully(tail);
        } catch (IOException e) {
            return false;
        }
        if (tail[tail.length - 2] != '\r' || tail[tail.length - 1] != '\n') {
            return false;
        }
        // RESP 的批量字符串长度前缀，命令流里必然大量出现；单看结尾 CRLF 可能误判
        String text = new String(tail, StandardCharsets.ISO_8859_1);
        return text.contains("\r\n$");
    }

    /** 只读文件头，避免把几百 MB 的错误文件整个交给解析器后才报错 */
    private boolean looksLikeRdb(File file) {
        try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = new byte[RDB_MAGIC.length];
            int read = in.read(head);
            if (read < RDB_MAGIC.length) {
                return false;
            }
            for (int i = 0; i < RDB_MAGIC.length; i++) {
                if (head[i] != RDB_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            logger.warn("delete offline file failed: {}", file.getAbsolutePath());
        }
    }
}
