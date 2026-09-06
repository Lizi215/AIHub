package com.aihub.ai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 配置。
 *
 * <p>默认值刻意偏保守：步数、超时、Token 预算都必须有上限（docs/AGENT.md §7），
 * 否则一次被注入的 README 就能让任务无限循环地烧钱。
 */
@ConfigurationProperties(prefix = "aihub.agent")
public class AgentProperties {

    /**
     * 执行模式：{@code AUTO}（有 api-key 用真实模型，否则 mock）/ {@code MOCK} / {@code OPENAI}。
     *
     * <p>{@code AUTO} 是默认值，也是本项目能在无任何 API Key 的环境里完整演示的原因
     * （docs/AGENT.md §7「无 Key 可运行」）。
     */
    private String mode = "AUTO";

    /** OpenAI 兼容端点。可指向 OpenAI / DeepSeek / 通义等兼容服务。 */
    private String baseUrl = "https://api.openai.com";

    /**
     * chat/completions 的路径（拼在 base-url 后）。
     *
     * <p>Spring AI 默认 {@code /v1/chat/completions}，但兼容服务的版本号并不统一，
     * 例如智谱是 {@code /v4/chat/completions}。因此做成可配置，部署哪个服务就配哪个版本。
     */
    private String completionsPath = "/v1/chat/completions";

    /** API Key。为空即视为无 Key，走 mock 模式；禁止写进代码或提交到 Git。 */
    private String apiKey = "";

    /** 模型名。 */
    private String model = "gpt-4o-mini";

    private Double temperature = 0.2;

    /** 单任务最大步数（Tool 调用次数），超出即终止。 */
    private int maxSteps = 12;

    /** 单任务超时：从开始执行到产出终态的总时长。 */
    private Duration taskTimeout = Duration.ofSeconds(60);

    /**
     * 单次 Tool 调用超时。
     *
     * <p>取 10s 而非 5s：ES/Feign 偶发抖动会让一次搜索超时，进而拖垮整个任务。
     * 守卫的意义是防失控，不是卡正常抖动；外层任务超时（taskTimeout）仍是硬上限。
     */
    private Duration toolTimeout = Duration.ofSeconds(10);

    /** 单任务 Token 预算（估算值参与判断）。 */
    private int maxTokens = 20000;

    /** 不可信内容（README / 帖子正文）进入上下文前，单个片段的最大字符数。 */
    private int maxUntrustedChars = 1500;

    /**
     * 是否启用模型思考模式（默认关）。
     *
     * <p>Qwen3 系列开启思考时会先生成一大段内部推理再输出决策 JSON。本项目让模型输出的只是
     * 结构化决策与文案，关闭思考既快又不损正确性。关闭方式见 {@code AiChatClientFactory}。
     */
    private boolean thinkingEnabled = false;

    /** 后台执行线程数。Agent 执行是 IO 密集 + 长耗时，必须与请求线程隔离。 */
    private int executorThreads = 4;

    /** 等待队列长度。满了直接拒绝，避免任务在队列里排到超时还不自知。 */
    private int executorQueueCapacity = 50;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCompletionsPath() {
        return completionsPath;
    }

    public void setCompletionsPath(String completionsPath) {
        this.completionsPath = completionsPath;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public Duration getTaskTimeout() {
        return taskTimeout;
    }

    public void setTaskTimeout(Duration taskTimeout) {
        this.taskTimeout = taskTimeout;
    }

    public Duration getToolTimeout() {
        return toolTimeout;
    }

    public void setToolTimeout(Duration toolTimeout) {
        this.toolTimeout = toolTimeout;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }

    public int getMaxUntrustedChars() {
        return maxUntrustedChars;
    }

    public void setMaxUntrustedChars(int maxUntrustedChars) {
        this.maxUntrustedChars = maxUntrustedChars;
    }

    public int getExecutorThreads() {
        return executorThreads;
    }

    public void setExecutorThreads(int executorThreads) {
        this.executorThreads = executorThreads;
    }

    public int getExecutorQueueCapacity() {
        return executorQueueCapacity;
    }

    public void setExecutorQueueCapacity(int executorQueueCapacity) {
        this.executorQueueCapacity = executorQueueCapacity;
    }

    /** 是否显式配置为 mock 模式。 */
    public boolean isMockForced() {
        return "MOCK".equalsIgnoreCase(mode);
    }

    /** 是否要求真实模型；此时缺 Key 属于配置错误，必须启动即报错。 */
    public boolean isModelRequired() {
        return "OPENAI".equalsIgnoreCase(mode);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
