package com.shcj.cache.interceptor;

import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.entity.OperationAudit;
import com.shcj.cache.web.service.OperationAuditService;
import com.shcj.cache.web.service.UserLoginStatusService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记录平台上的所有变更操作（POST/PUT/DELETE/PATCH），供审计页面查询。
 */
public class OperationAuditInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationAuditInterceptor.class);

    private static final String START_TIME_ATTR = "cachecloud.audit.startTime";

    private static final String OBJECT_LABEL_ATTR = OperationAuditInterceptor.class.getName() + ".OBJECT_LABEL";

    private static final int MAX_OBJECT_LABEL_LENGTH = 255;

    /** 需要脱敏的参数名片段 */
    private static final String[] SENSITIVE_KEYS = {"password", "passwd", "pwd", "token", "secret", "apikey", "api_key"};

    private static final int MAX_PARAM_LENGTH = 4000;
    private static final int MAX_ERROR_LENGTH = 1000;

    /** 超过这个大小的响应不解析，避免为审计缓冲大响应 */
    private static final int MAX_RESPONSE_PARSE_LENGTH = 64 * 1024;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private UserLoginStatusService userLoginStatusService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isMutating(request)) {
            request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
            // 必须在业务处理之前解析：删除类操作会把被引用的记录一并带走，
            // 等到 afterCompletion 再查，删除任务/集群/配置的那一条自己就查不到对象了
            captureObjectLabel(request);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!isMutating(request)) {
            return;
        }
        try {
            operationAuditService.record(buildAudit(request, response, handler, ex));
        } catch (Exception e) {
            LOGGER.warn("build operation audit failed uri={}: {}", request.getRequestURI(), e.getMessage());
        }
    }

    private OperationAudit buildAudit(HttpServletRequest request, HttpServletResponse response,
                                      Object handler, Exception ex) {
        String uri = request.getRequestURI();
        Map<String, String> params = collectParams(request);

        OperationAudit audit = new OperationAudit();
        audit.setUserName(resolveUserName(request, params));
        audit.setModule(resolveModule(uri));
        audit.setHttpMethod(request.getMethod());
        audit.setRequestUri(uri);
        audit.setHandler(resolveHandler(handler));
        audit.setAppId(resolveId(uri, params, "apps", "appId"));
        audit.setInstanceId(resolveId(uri, params, "instances", "instanceId"));
        audit.setObjectLabel(resolveObjectLabel(request, uri, params));
        audit.setParams(truncate(new JSONObject(new LinkedHashMap<String, Object>(params)).toJSONString(), MAX_PARAM_LENGTH));
        audit.setClientIp(resolveClientIp(request));

        int status = response.getStatus();
        audit.setStatusCode(status);
        if (ex != null) {
            audit.setSuccess(0);
            audit.setErrorMsg(truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage(), MAX_ERROR_LENGTH));
        } else if (status >= 400) {
            audit.setSuccess(0);
            audit.setErrorMsg(truncate(resolveBusinessMessage(response), MAX_ERROR_LENGTH));
        } else {
            // 接口普遍返回 HTTP 200，真正的成败在响应体的 code(新接口)/status(遗留接口) 字段里
            String failure = resolveBusinessFailure(response);
            audit.setSuccess(failure == null ? 1 : 0);
            audit.setErrorMsg(truncate(failure, MAX_ERROR_LENGTH));
        }

        Object startTime = request.getAttribute(START_TIME_ATTR);
        audit.setCostMs(startTime instanceof Long ? System.currentTimeMillis() - (Long) startTime : 0L);
        audit.setCreateTime(new Date());
        return audit;
    }

    /**
     * 解析响应体判定业务结果：
     * 新接口 ApiResponse 的 code 非 0、遗留 AjaxResult 的 status 非 1 都视为失败。
     *
     * @return null 表示成功，否则返回失败原因
     */
    private String resolveBusinessFailure(HttpServletResponse response) {
        JSONObject body = readResponseJson(response);
        if (body == null) {
            return null;
        }
        if (body.containsKey("code")) {
            Integer code = toInteger(body.get("code"));
            if (code != null && code != 0) {
                return "code=" + code + " " + StringUtils.defaultString(body.getString("message"));
            }
            return null;
        }
        if (body.containsKey("status")) {
            Integer statusValue = toInteger(body.get("status"));
            if (statusValue != null && statusValue != 1) {
                return "status=" + statusValue + " " + StringUtils.defaultString(body.getString("message"));
            }
        }
        return null;
    }

    private String resolveBusinessMessage(HttpServletResponse response) {
        JSONObject body = readResponseJson(response);
        if (body == null) {
            return null;
        }
        return StringUtils.defaultIfBlank(body.getString("message"), body.toJSONString());
    }

    private JSONObject readResponseJson(HttpServletResponse response) {
        ContentCachingResponseWrapper wrapper =
                WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        if (wrapper == null) {
            return null;
        }
        byte[] content = wrapper.getContentAsByteArray();
        if (content == null || content.length == 0 || content.length > MAX_RESPONSE_PARSE_LENGTH) {
            return null;
        }
        try {
            String text = new String(content, StringUtils.defaultIfBlank(wrapper.getCharacterEncoding(), "UTF-8")).trim();
            if (!text.startsWith("{")) {
                return null;
            }
            return JSONObject.parseObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isMutating(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method) || "PATCH".equals(method);
    }

    private String resolveUserName(HttpServletRequest request, Map<String, String> params) {
        String userName = userLoginStatusService.getUserNameFromLoginStatus(request);
        if (StringUtils.isNotBlank(userName)) {
            return userName;
        }
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        // 登录请求此时还没有登录态（Cookie 写在响应里），退回到请求中的用户名
        return StringUtils.defaultString(resolveUserNameFromParams(params));
    }

    private String resolveUserNameFromParams(Map<String, String> params) {
        String userName = StringUtils.defaultIfBlank(params.get("username"), params.get("userName"));
        if (StringUtils.isNotBlank(userName)) {
            return userName;
        }
        String body = params.get("_body");
        if (StringUtils.isBlank(body) || !body.trim().startsWith("{")) {
            return null;
        }
        try {
            JSONObject json = JSONObject.parseObject(body);
            return StringUtils.defaultIfBlank(json.getString("username"), json.getString("userName"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取业务域：/api/v1/apps/... → apps；/manage/instance/... → manage/instance。
     */
    /**
     * 文件上传的作用对象只体现在上传的文件本身上。
     *
     * <p>multipart 请求的 body 不会进 params，不记下文件名的话，审计里这类操作的
     * 「操作对象」只能是空的。在 afterCompletion 里读取是安全的：此时 Spring 尚未
     * 清理 multipart，且请求体早已被 Controller 消费完，不会影响上传本身。</p>
     */
    private void collectUploadFileNames(HttpServletRequest request, Map<String, String> params) {
        if (!(request instanceof MultipartHttpServletRequest)) {
            return;
        }
        try {
            MultipartHttpServletRequest multipart = (MultipartHttpServletRequest) request;
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = multipart.getFileNames(); it.hasNext(); ) {
                MultipartFile file = multipart.getFile(it.next());
                if (file != null && StringUtils.isNotBlank(file.getOriginalFilename())) {
                    names.add(file.getOriginalFilename());
                }
            }
            if (!names.isEmpty()) {
                params.put("uploadFileName", String.join(", ", names));
            }
        } catch (Exception e) {
            LOGGER.debug("collect upload file name failed: {}", e.getMessage());
        }
    }

    /**
     * 请求处理前先把依赖数据库的那部分操作对象解析出来，存进请求属性。
     *
     * <p>只解析会被本次请求改动的那些来源（集群、节点、迁移任务、报警配置、
     * 离线分析记录）。请求体里的字段不依赖库中状态，留到落库时再取也一样。</p>
     */
    private void captureObjectLabel(HttpServletRequest request) {
        try {
            String uri = request.getRequestURI();
            Map<String, String> pathParams = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
                String[] value = entry.getValue();
                pathParams.put(entry.getKey(), value == null || value.length == 0 ? "" : value[0]);
            }
            Long appId = resolveId(uri, pathParams, "apps", "appId");
            Long instanceId = resolveId(uri, pathParams, "instances", "instanceId");
            String label = operationAuditService.resolveStatefulObjectLabel(uri, appId, instanceId);
            if (StringUtils.isNotBlank(label)) {
                request.setAttribute(OBJECT_LABEL_ATTR, label);
            }
        } catch (Exception e) {
            // 审计取不到对象不能影响正常请求
            LOGGER.debug("capture audit object label failed: {}", e.getMessage());
        }
    }

    /**
     * 最终写入审计的操作对象：优先用处理前抓到的快照，否则退回请求体里的信息。
     */
    private String resolveObjectLabel(HttpServletRequest request, String uri, Map<String, String> params) {
        Object captured = request.getAttribute(OBJECT_LABEL_ATTR);
        if (captured instanceof String && StringUtils.isNotBlank((String) captured)) {
            return truncate((String) captured, MAX_OBJECT_LABEL_LENGTH);
        }
        try {
            String label = operationAuditService.resolveStatelessObjectLabel(uri, params);
            return StringUtils.isBlank(label) ? null : truncate(label, MAX_OBJECT_LABEL_LENGTH);
        } catch (Exception e) {
            LOGGER.debug("resolve audit object label failed: {}", e.getMessage());
            return null;
        }
    }

    private String resolveModule(String uri) {
        String[] segments = StringUtils.split(uri, '/');
        if (segments == null || segments.length == 0) {
            return "";
        }
        if ("api".equals(segments[0])) {
            return segments.length >= 3 ? segments[2] : "api";
        }
        return segments.length >= 2 ? segments[0] + "/" + segments[1] : segments[0];
    }

    private String resolveHandler(Object handler) {
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            return handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
        }
        return null;
    }

    /**
     * 优先从 /{segment}/{id} 形式的路径里取，其次取同名请求参数。
     */
    private Long resolveId(String uri, Map<String, String> params, String pathSegment, String paramName) {
        String[] segments = StringUtils.split(uri, '/');
        if (segments != null) {
            for (int i = 0; i < segments.length - 1; i++) {
                if (pathSegment.equals(segments[i])) {
                    Long id = parseLong(segments[i + 1]);
                    if (id != null) {
                        return id;
                    }
                }
            }
        }
        return parseLong(params.get(paramName));
    }

    private Long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, String> collectParams(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        collectUploadFileNames(request, params);
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String value = entry.getValue() == null ? "" : String.join(",", Arrays.asList(entry.getValue()));
            params.put(entry.getKey(), maskIfSensitive(entry.getKey(), value));
        }
        String body = readBody(request);
        if (StringUtils.isNotBlank(body)) {
            params.put("_body", maskBody(body));
        }
        return params;
    }

    private String readBody(HttpServletRequest request) {
        if (!(request instanceof ContentCachingRequestWrapper)) {
            return null;
        }
        byte[] content = ((ContentCachingRequestWrapper) request).getContentAsByteArray();
        if (content == null || content.length == 0) {
            return null;
        }
        String encoding = request.getCharacterEncoding();
        try {
            return new String(content, StringUtils.isBlank(encoding) ? "UTF-8" : encoding);
        } catch (UnsupportedEncodingException e) {
            return new String(content);
        }
    }

    /**
     * JSON 请求体逐字段脱敏；非 JSON 内容原样保留（仍受长度截断保护）。
     */
    private String maskBody(String body) {
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            JSONObject json = JSONObject.parseObject(trimmed);
            for (String key : json.keySet().toArray(new String[0])) {
                if (isSensitive(key)) {
                    json.put(key, "***");
                }
            }
            return json.toJSONString();
        } catch (Exception e) {
            return trimmed;
        }
    }

    private String maskIfSensitive(String key, String value) {
        return isSensitive(key) ? "***" : value;
    }

    private boolean isSensitive(String key) {
        if (StringUtils.isBlank(key)) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String sensitive : SENSITIVE_KEYS) {
            if (lower.contains(sensitive)) {
                return true;
            }
        }
        return false;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(forwarded)) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.isNotBlank(realIp) ? realIp.trim() : request.getRemoteAddr();
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...(truncated)";
    }
}
