package com.shcj.cache.alert.impl;

import com.shcj.cache.alert.KafkaComponent;
import com.shcj.cache.alert.vo.KafkaAlertVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka 告警空实现，用于暂停 Kafka 推送时不连接 broker。
 */
public class NoOpKafkaComponent implements KafkaComponent {

    private static final Logger logger = LoggerFactory.getLogger(NoOpKafkaComponent.class);

    @Override
    public void sendAlert(KafkaAlertVO kafkaAlertVO) {
        if (logger.isTraceEnabled()) {
            logger.trace("Kafka alert skipped (disabled): {}",
                    kafkaAlertVO != null ? kafkaAlertVO.getTitle() : null);
        }
    }
}
