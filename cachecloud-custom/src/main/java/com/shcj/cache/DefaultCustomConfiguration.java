package com.shcj.cache;

import com.shcj.cache.alert.EmailComponent;
import com.shcj.cache.alert.KafkaComponent;
import com.shcj.cache.alert.WeChatComponent;
import com.shcj.cache.alert.impl.DefaultEmailComponent;
import com.shcj.cache.alert.impl.DefaultKafkaComponent;
import com.shcj.cache.alert.impl.DefaultWeChatComponent;
import com.shcj.cache.alert.impl.NoOpKafkaComponent;
import com.shcj.cache.report.ReportDataComponent;
import com.shcj.cache.report.impl.DefaultReportDataComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Created by yijunzhang
 */
@Configuration
public class DefaultCustomConfiguration {

    @Bean("emailComponent")
    @ConditionalOnMissingBean
    public EmailComponent emailComponent() {
        return new DefaultEmailComponent();
    }

    @Bean("weChatComponent")
    @ConditionalOnMissingBean
    public WeChatComponent weChatComponent() {
        return new DefaultWeChatComponent();
    }

    @Bean("reportDataComponent")
    @ConditionalOnMissingBean
    public ReportDataComponent reportDataComponent() {
        return new DefaultReportDataComponent();
    }

    @Bean("kafkaComponent")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cachecloud.kafka.enabled", havingValue = "true")
    public KafkaComponent kafkaComponent() {
        return new DefaultKafkaComponent();
    }

    @Bean("kafkaComponent")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cachecloud.kafka.enabled", havingValue = "false", matchIfMissing = true)
    public KafkaComponent noOpKafkaComponent() {
        return new NoOpKafkaComponent();
    }

}
