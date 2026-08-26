-- 包名 com.njcb.* -> com.shcj.* 的配套数据迁移。
--
-- Quartz 使用 JDBC JobStore，作业实现类的全限定名持久化在 QRTZ_JOB_DETAILS.JOB_CLASS_NAME。
-- 只改代码不改这张表，调度器启动时会对每个作业抛 ClassNotFoundException 并把触发器置为 ERROR。
-- 幂等，可重复执行。
--
-- 执行顺序：先停应用 -> 跑本脚本 -> 部署新包启动。若在旧进程仍运行时执行，旧进程会因
-- 找不到 com.shcj.* 而把触发器置为 ERROR，脚本末尾的重置语句用于兜底。

UPDATE QRTZ_JOB_DETAILS
SET JOB_CLASS_NAME = REPLACE(JOB_CLASS_NAME, 'com.njcb.', 'com.shcj.')
WHERE JOB_CLASS_NAME LIKE 'com.njcb.%';

-- 兜底：迁移期间被旧进程置为 ERROR 的触发器恢复为可调度状态，否则对应作业不再触发。
UPDATE QRTZ_TRIGGERS SET TRIGGER_STATE = 'WAITING' WHERE TRIGGER_STATE = 'ERROR';

-- 说明：全库文本列已扫描，除 QRTZ_JOB_DETAILS.JOB_CLASS_NAME 外无其他存放包名的位置。
-- （历史上 system_config 表里有个 'cachecloud.njcb.tokens' 配置键，该表已随
--   SystemConfigRefreshJob 一并废弃，无需处理。）
