package com.shcj.cache.web.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.web.service.AiChatService;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service("aiChatService")
public class DeepSeekAiChatServiceImpl implements AiChatService {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekAiChatServiceImpl.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String SYSTEM_PROMPT =
            "你是 CacheCloud Redis 管理平台的运维助手。你擅长 Redis 监控、内存分析、慢查询、"
                    + "主从/哨兵/集群拓扑、外部纳管与故障排查。回答请简洁、分步骤、使用中文，可用 Markdown 标题和列表。"
                    + "若用户消息中含【平台实时数据】，请结合这些数据作答，不要编造未提供的指标。"
                    + "涉及 DEL、FLUSH、重启等有风险操作时必须明确警告。"
                    + "若缺少实例数据，请说明需要用户提供 appId、instanceId 或 ip:port。";

    @Value("${cachecloud.ai.enabled:false}")
    private boolean enabled;

    @Value("${cachecloud.ai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${cachecloud.ai.api-key:}")
    private String apiKey;

    @Value("${cachecloud.ai.model:deepseek-chat}")
    private String model;

    @Value("${cachecloud.ai.max-tokens:2048}")
    private int maxTokens;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public boolean isEnabled() {
        return enabled && StringUtils.isNotBlank(apiKey);
    }

    @Override
    public String chat(List<Map<String, String>> messages) {
        if (!isEnabled()) {
            throw new IllegalStateException("AI 助手未启用，请配置 cachecloud.ai.enabled 与 api-key");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        JSONArray msgArray = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);
        msgArray.add(system);

        for (Map<String, String> item : messages) {
            if (item == null) {
                continue;
            }
            String role = StringUtils.trimToEmpty(item.get("role"));
            String content = StringUtils.trimToEmpty(item.get("content"));
            if (StringUtils.isBlank(content)) {
                continue;
            }
            if (!"user".equals(role) && !"assistant".equals(role)) {
                role = "user";
            }
            JSONObject msg = new JSONObject();
            msg.put("role", role);
            msg.put("content", content);
            msgArray.add(msg);
        }
        if (msgArray.size() <= 1) {
            throw new IllegalArgumentException("消息不能为空");
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", msgArray);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        String url = StringUtils.removeEnd(baseUrl, "/") + "/chat/completions";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                logger.warn("DeepSeek API error status={} body={}", response.code(), abbreviate(respBody));
                throw new IllegalStateException(parseApiError(respBody, response.code()));
            }
            JSONObject json = JSON.parseObject(respBody);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException("AI 返回为空");
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            if (message == null) {
                throw new IllegalStateException("AI 返回格式异常");
            }
            return StringUtils.defaultString(message.getString("content"));
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            logger.error("DeepSeek chat failed: {}", e.getMessage());
            throw new IllegalStateException("AI 服务调用失败: " + e.getMessage());
        }
    }

    private String parseApiError(String body, int code) {
        try {
            JSONObject json = JSON.parseObject(body);
            JSONObject error = json.getJSONObject("error");
            if (error != null && StringUtils.isNotBlank(error.getString("message"))) {
                String msg = error.getString("message");
                if (code == 402 || containsIgnoreCase(msg, "Insufficient Balance")) {
                    return "AI 账户余额不足，请到 DeepSeek 开放平台充值或更换 API Key";
                }
                if (code == 401 || containsIgnoreCase(msg, "Authentication")) {
                    return "AI API Key 无效或已过期，请检查 cachecloud.ai.api-key 配置";
                }
                return "AI 服务暂时不可用(" + code + ")，请稍后重试";
            }
        } catch (Exception ignored) {
            // ignore parse error
        }
        if (code == 402) {
            return "AI 账户余额不足，请到 DeepSeek 开放平台充值或更换 API Key";
        }
        return "AI 服务暂时不可用(" + code + ")，请稍后重试";
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        return text != null && keyword != null && text.toLowerCase().contains(keyword.toLowerCase());
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
