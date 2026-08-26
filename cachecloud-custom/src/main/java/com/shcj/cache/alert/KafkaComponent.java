package com.shcj.cache.alert;

import com.shcj.cache.alert.vo.KafkaAlertVO;

/**
 * kafka告警组件接口
 *
 * @author zoushunqing 2023/2/16 19:19
 * @since Dev_1.0.1
 */
public interface KafkaComponent {

    void sendAlert(KafkaAlertVO kafkaAlertVO);

}
