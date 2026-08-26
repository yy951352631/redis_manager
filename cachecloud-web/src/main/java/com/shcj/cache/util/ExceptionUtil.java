package com.shcj.cache.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 异常工具类
 *
 * @author zoushunqing 2023/2/9 17:36
 * @since Dev_1.0.1
 */
public class ExceptionUtil {

    public static String getStackTraceInfo(Exception e) {

        StringWriter sw = null;
        PrintWriter pw = null;

        try {
            sw = new StringWriter();
            pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            sw.flush();

            return sw.toString();
        } catch (Exception ex) {
            return "发生错误";
        } finally {
            if (sw != null) {
                try {
                    sw.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
            if (pw != null) {
                pw.close();
            }
        }
    }
}
