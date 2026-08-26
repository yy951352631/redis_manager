package com.shcj.cache;

import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.quartz.QuartzEndpointAutoConfiguration;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.ImportResource;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Created by zhangyijun
 */
@SpringBootApplication
// QuartzEndpoint（Boot 2.5+ 新增）按类型注入 Scheduler，本应用有 jvm/cluster 两个调度器会冲突
@EnableAutoConfiguration(exclude = {MybatisAutoConfiguration.class, QuartzEndpointAutoConfiguration.class})
@ImportResource("${spring.application.import}")
@ServletComponentScan(basePackages = "com.shcj.cache.web.druid")
@EnableAsync
public class ApplicationStarter extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(ApplicationStarter.class);
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ApplicationStarter.class);
        app.setAdditionalProfiles();
        app.setBannerMode(Banner.Mode.LOG);
        app.run(args);
    }
}
