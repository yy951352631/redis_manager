package com.shcj.cache.util;

import java.util.concurrent.TimeUnit;

/**
 * 线程工具类
 *
 * @author zoushunqing 2023/5/17 8:49
 * @since Dev_1.0.1
 */
public class ThreadUtil {

    public static void sleepSec(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
