package com.shcj.cache.web.config;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 跨域配置。
 *
 * <p>同源部署（Nginx 同时托管 SPA 与反向代理，且 {@code proxy_set_header Host $http_host} 保留端口）
 * 根本不会触发浏览器的跨域检查，这里的白名单只对真正跨来源的访问生效：本地 dev server、
 * 前后端分离到不同域名/端口的部署等。
 *
 * <p>来源列表由 {@code cachecloud.cors.allowed-origins} 配置，逗号分隔，支持
 * {@code http://192.168.*.*:*} 这类通配（走 Spring 的 allowedOriginPatterns）。
 * 留空表示不允许任何跨来源访问。注意：本配置开启了 allowCredentials，
 * <b>不要</b>配成 {@code *}，否则等同于把带 Cookie 的接口对所有站点开放。
 */
@Configuration
public class ApiCorsConfig implements WebMvcConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiCorsConfig.class);

    /** SPA 会访问的路径：/api/v1 为主，其余是尚未迁完的遗留 ajax 接口 */
    private static final String[] CORS_PATHS = {"/api/v1/**", "/manage/**", "/admin/**"};

    @Autowired
    private AppIdPathVariableResolver appIdPathVariableResolver;

    @Value("${cachecloud.cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(appIdPathVariableResolver);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = parseOrigins();
        if (origins.isEmpty()) {
            LOGGER.info("cachecloud.cors.allowed-origins 未配置，仅接受同源请求");
            return;
        }
        LOGGER.info("CORS allowed origins: {}", origins);
        String[] originPatterns = origins.toArray(new String[0]);
        for (String path : CORS_PATHS) {
            registry.addMapping(path)
                    .allowedOriginPatterns(originPatterns)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true)
                    .maxAge(3600);
        }
    }

    private List<String> parseOrigins() {
        if (StringUtils.isBlank(allowedOrigins)) {
            return new ArrayList<>();
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }
}
