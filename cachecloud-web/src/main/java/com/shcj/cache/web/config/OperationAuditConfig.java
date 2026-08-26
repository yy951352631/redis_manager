package com.shcj.cache.web.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 操作审计所需的基础设施：请求体缓存过滤器 + 落库线程池。
 */
@Configuration
public class OperationAuditConfig {

    /**
     * 变更请求包一层 ContentCaching 包装器，使审计拦截器能在请求结束后读到 JSON 请求体与响应体。
     * 本项目的接口一律返回 HTTP 200，成功与否在响应体的 code/status 字段里，因此响应体也必须缓存。
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> operationAuditBodyCachingFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                if (!shouldCache(request)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
                try {
                    filterChain.doFilter(new ContentCachingRequestWrapper(request), responseWrapper);
                } finally {
                    // 审计拦截器在 afterCompletion 里读完缓存后，这里把响应体真正写回客户端
                    responseWrapper.copyBodyToResponse();
                }
            }

            private boolean shouldCache(HttpServletRequest request) {
                String method = request.getMethod();
                boolean mutating = "POST".equals(method) || "PUT".equals(method)
                        || "DELETE".equals(method) || "PATCH".equals(method);
                if (!mutating) {
                    return false;
                }
                // 文件上传交给 multipart 解析器处理，不做缓存包装
                String contentType = request.getContentType();
                return contentType == null || !contentType.toLowerCase().startsWith("multipart/");
            }
        });
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("operationAuditBodyCachingFilter");
        return registration;
    }

    /**
     * 审计落库专用线程池：队列满时退回调用线程执行，宁可慢一点也不丢审计记录。
     */
    @Bean("auditTaskExecutor")
    public TaskExecutor auditTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("operation-audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
