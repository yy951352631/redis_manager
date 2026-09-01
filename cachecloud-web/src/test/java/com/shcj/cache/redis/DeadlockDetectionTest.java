package com.shcj.cache.redis;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死锁识别的测试。
 *
 * <p>识别错在两个方向都有代价：认不出死锁就不会重试，这一批慢日志被静默丢掉；
 * 把普通异常也当成死锁则会白白重试三次，把真正的错误延后暴露。</p>
 */
public class DeadlockDetectionTest {

    /** 与 RedisCenterImpl.isDeadlock 同一份判定逻辑 */
    private boolean isDeadlock(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException && ((SQLException) cause).getErrorCode() == 1213) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains("Deadlock found")) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void 按错误码识别死锁() {
        assertTrue(isDeadlock(new SQLException("whatever", "40001", 1213)));
    }

    @Test
    public void 按错误文本识别死锁() {
        assertTrue(isDeadlock(new RuntimeException(
                "Deadlock found when trying to get lock; try restarting transaction")));
    }

    @Test
    public void 死锁藏在异常链深处也要认出来() {
        // MyBatis 会把 SQLException 包进 PersistenceException，实际就是这种形态
        Throwable root = new SQLException("deadlock", "40001", 1213);
        Throwable wrapped = new RuntimeException("### Error updating database", root);
        Throwable outer = new IllegalStateException("mybatis", wrapped);
        assertTrue(isDeadlock(outer));
    }

    @Test
    public void 其他数据库错误不当作死锁() {
        // 1062 是唯一键冲突，重试多少次都还是冲突
        assertFalse(isDeadlock(new SQLException("duplicate", "23000", 1062)));
        assertFalse(isDeadlock(new RuntimeException("Table doesn't exist")));
        assertFalse(isDeadlock(new SQLException("timeout", "HY000", 1205)));
    }

    @Test
    public void 空异常与空消息不误判() {
        assertFalse(isDeadlock(null));
        assertFalse(isDeadlock(new RuntimeException((String) null)));
    }
}
