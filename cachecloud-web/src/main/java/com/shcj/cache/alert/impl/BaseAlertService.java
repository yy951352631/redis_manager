package com.shcj.cache.alert.impl;

import com.shcj.cache.alert.EmailComponent;
import com.shcj.cache.alert.KafkaComponent;
import com.shcj.cache.alert.WeChatComponent;
import com.shcj.cache.alert.vo.KafkaAlertVO;
import com.shcj.cache.web.service.AppAlertRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 报警基类
 *
 * @author leifu
 * @Date 2014年12月16日
 * @Time 下午4:15:11
 */
public class BaseAlertService {
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * 邮箱报警
     */
    @Autowired(required = false)
    protected EmailComponent emailComponent;

    /**
     * 报警记录
     */
    @Autowired(required = false)
    protected AppAlertRecordService appAlertRecordService;

    /**
     * 微信报警
     */
    @Autowired(required = false)
    protected WeChatComponent weChatComponent;

    @Autowired(required = false)
    protected KafkaComponent kafkaComponent;

    protected void sendKafkaAlert(KafkaAlertVO kafkaAlertVO) {
        if (kafkaComponent != null && kafkaAlertVO != null) {
            kafkaComponent.sendAlert(kafkaAlertVO);
        }
    }

}
