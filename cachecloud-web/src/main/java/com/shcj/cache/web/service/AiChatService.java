package com.shcj.cache.web.service;

import java.util.List;
import java.util.Map;

/**
 * AI 对话（DeepSeek 等 OpenAI 兼容接口）。
 */
public interface AiChatService {

    boolean isEnabled();

    String chat(List<Map<String, String>> messages);
}
