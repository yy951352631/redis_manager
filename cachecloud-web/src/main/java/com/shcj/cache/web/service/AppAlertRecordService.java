package com.shcj.cache.web.service;

import com.shcj.cache.entity.AppAlertRecord;
import com.shcj.cache.web.controller.api.dto.AlertRecordPageDto;
import com.shcj.cache.web.enums.AlertTypeEnum;

import java.util.Date;
import java.util.List;

/**
 * 保存报警信息
 * @author zengyizhao
 * @Date 2021年9月3日
 */
public interface AppAlertRecordService {
    
    /**
     * 保存报警信息
     *
     * @param appAlertRecord
     * @return
     */
    int saveAlertInfo(AppAlertRecord appAlertRecord);

    /**
     * 批量保存报警信息
     * @param appAlertRecordList
     * @return
     */
    int saveBatchAlertInfo(List<AppAlertRecord> appAlertRecordList);

    /**
     * 异步保存报警信息
     *
     * @param appAlertRecord
     * @return
     */
    void asyncSaveAlertInfo(AppAlertRecord appAlertRecord);

    /**
     * 异步批量保存报警信息
     * @param appAlertRecordList
     * @return
     */
    void asyncSaveBatchAlertInfo(List<AppAlertRecord> appAlertRecordList);

    /**
     * 根据报警类型，保存报警信息
     * @param type 类型
     * @param title 邮件标题
     * @param message 邮件内容
     * @param object
     * @return
     */
    int saveAlertInfoByType(AlertTypeEnum type, String title, String message, Object... object);

    /**
     * 同 {@link #saveAlertInfoByType}，但用报警配置里的重要程度覆盖类型自带的默认值。
     *
     * @param importantLevel 报警配置的 important_level
     */
    int saveAlertInfoByTypeWithLevel(AlertTypeEnum type, int importantLevel, String title, String message,
                                     Object... object);

    /**
     * 分页查询报警记录，所有条件均可为空。
     *
     * @param importantLevel 重要程度（0/1/2），null 表示不限
     * @param keyword        标题或内容关键字
     */
    AlertRecordPageDto search(Integer importantLevel, Long appId, String ip,
                              String keyword, Date startTime, Date endTime, int pageNo, int pageSize);

}
