package com.shcj.cache.web.config;

import com.shcj.cache.exception.BizException;
import com.shcj.cache.web.service.AppService;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 将路径参数 appId 解析为内部 app_id（支持传入 cluster_no）。
 */
@Component
public class AppIdPathVariableResolver implements HandlerMethodArgumentResolver {

    @Autowired
    private AppService appService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(PathVariable.class)) {
            return false;
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable == null) {
            return false;
        }
        String name = StringUtils.isNotBlank(pathVariable.value())
                ? pathVariable.value()
                : pathVariable.name();
        if (StringUtils.isBlank(name)) {
            name = parameter.getParameterName();
        }
        if (!"appId".equals(name)) {
            return false;
        }
        Class<?> type = parameter.getParameterType();
        return type == long.class || type == Long.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        @SuppressWarnings("unchecked")
        Map<String, String> uriVars = (Map<String, String>) webRequest.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (uriVars == null || !uriVars.containsKey("appId")) {
            throw new BizException("缺少路径参数 appId");
        }
        long idOrClusterNo = Long.parseLong(uriVars.get("appId"));
        Long appId = appService.resolveAppId(idOrClusterNo);
        if (appId == null) {
            throw new BizException("集群不存在: " + idOrClusterNo);
        }
        Class<?> type = parameter.getParameterType();
        return type == long.class ? appId.longValue() : appId;
    }
}
