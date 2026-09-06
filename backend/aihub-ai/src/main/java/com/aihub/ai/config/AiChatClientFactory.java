package com.aihub.ai.config;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 真实模型（OpenAI 兼容端点）客户端工厂。
 *
 * <p>自己构造 Bean、不用 starter 自动装配：OpenAI 自动配置在缺 api-key 时会直接让上下文启动失败，
 * 而本项目要求「没有 Key 也能启动」。这里按需懒构造——有 Key 才建 ChatClient，没有就走 mock。
 * 另外 {@code mode=OPENAI} 却没给 Key 视为配置错误，构造阶段直接失败。
 */
@Component
public class AiChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(AiChatClientFactory.class);

    private final AgentProperties properties;

    private volatile ChatClient chatClient;

    public AiChatClientFactory(AgentProperties properties) {
        this.properties = properties;
        if (properties.isModelRequired() && !properties.hasApiKey()) {
            throw new IllegalStateException(
                    "aihub.agent.mode=OPENAI requires aihub.agent.api-key, but it is empty");
        }
        if (properties.isModelRequired()) {
            log.info("agent real model enabled, model={}, baseUrl={}", properties.getModel(), properties.getBaseUrl());
        } else if (!properties.hasApiKey()) {
            log.info("agent runs in mock mode: no api key configured (docs/AGENT.md §7)");
        }
    }

    /** 是否具备调用真实模型的条件。 */
    public boolean available() {
        return !properties.isMockForced() && properties.hasApiKey();
    }

    /** 取客户端；不可用时抛业务异常，调用方应先判断 {@link #available()}。 */
    public ChatClient requireClient() {
        if (!available()) {
            throw new BizException(ErrorCode.AGENT_SCENE_UNSUPPORTED,
                    "real model is not configured; agent runs in mock mode");
        }
        ChatClient local = chatClient;
        if (local == null) {
            synchronized (this) {
                local = chatClient;
                if (local == null) {
                    local = createClient();
                    chatClient = local;
                }
            }
        }
        return local;
    }

    private ChatClient createClient() {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                // 各家的路径不同（OpenAI 是 /v1、智谱是 /v4），必须可配
                .completionsPath(properties.getCompletionsPath())
                .apiKey(properties.getApiKey())
                .build();
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(properties.getTemperature());
        // 关掉思考模式：Qwen3 默认先输出一大段推理再作答，规划一轮要 45~190s。
        // DashScope 兼容模式下 enable_thinking 不是标准参数，得走 extra_body。
        if (!properties.isThinkingEnabled()) {
            options.extraBody(Map.of("enable_thinking", false));
        }
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options.build())
                .build();
        log.info("agent chat client created, model={}", properties.getModel());
        return ChatClient.builder(model).build();
    }
}
