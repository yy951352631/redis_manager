package com.shcj.cache.alert.impl;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.alert.KafkaComponent;
import com.shcj.cache.alert.vo.KafkaAlertVO;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.util.Properties;

/**
 * kafka告警组件接口
 *
 * @author zoushunqing 2023/2/16 19:36
 * @since Dev_1.0.1
 */
public class DefaultKafkaComponent implements KafkaComponent {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private Producer<String, String> producer;

    private volatile boolean isInit = false;

    @Value("${cachecloud.kafka.bootstrap-servers}")
    private String bootStrapServers;

    @Value("${cachecloud.kafka.topicName}")
    private String topicName;

    @PostConstruct
    public void init() {
        try {
            Properties properties = new Properties();
            properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootStrapServers);
            properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producer = new KafkaProducer<>(properties);
            logger.info("DefaultKafkaComponent {} init() succeed.", bootStrapServers);
            isInit = true;
        } catch (Exception ex) {
            logger.error("Kafka init failed. ", ex);
        }
    }


    @Override
    public void sendAlert(KafkaAlertVO kafkaAlertVO) {
        logger.error("KafkaAlertVO -> {}", kafkaAlertVO);
        if (isInit) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topicName, JSON.toJSONString(kafkaAlertVO));
            try {
                producer.send(record);
            } catch (Exception ex) {
                logger.error("kafka sendAlert failed. ", ex);
            }
        }
    }
}
