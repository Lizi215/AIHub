package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.agent.tool.AgentToolCatalog;
import com.aihub.ai.config.AgentProperties;
import com.aihub.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link SpringAiAgentPlanner#parseDecision(String)} 的解析兜底逻辑。
 * 只测解析：模型调用链路（ChatClient）不在单测范围（需要真实 Key）。
 */
class SpringAiAgentPlannerTest {

    private final SpringAiAgentPlanner planner = new SpringAiAgentPlanner(
            null, mock(AgentToolCatalog.class), new AgentProperties(), new ObjectMapper());

    @Test
    @DisplayName("正常决策 JSON 直接解析")
    void shouldParsePlainDecisionJson() {
        String raw = """
                {"toolCalls":[{"tool":"search_assets","args":{"keyword":"spring"},"intent":"搜索"}]}
                """;
        SpringAiAgentPlanner.PlannerDecisionDto dto = planner.parseDecision(raw);
        assertThat(dto.toolCalls()).hasSize(1);
        assertThat(dto.toolCalls().get(0).tool()).isEqualTo("search_assets");
    }

    @Test
    @DisplayName("宽容解析：README 里带真实换行（未转义）的决策 JSON 也能解析")
    void shouldParseJsonWithRawNewlinesInStrings() {
        // 模拟模型的常见输出：readme 值里有真实换行符（未转义成 \n），严格 JSON 不合法
        String raw = "{\"plan\":{\"summary\":\"打标签\",\"steps\":[],\"cannotFulfill\":false,"
                + "\"readmeDraft\":\"# 标题\n\n第一行\n第二行\"}}";
        SpringAiAgentPlanner.PlannerDecisionDto dto = planner.parseDecision(raw);
        assertThat(dto.plan()).isNotNull();
        assertThat(dto.plan().summary()).isEqualTo("打标签");
    }

    @Test
    @DisplayName("能力不足声明：cannotFulfill=true 的空计划被解析（不触发空计划纠正）")
    void shouldParseCannotFulfill() {
        String raw = """
                {"plan":{"summary":"无法完成：本系统没有读取本地磁盘的能力","steps":[],"cannotFulfill":true}}
                """;
        SpringAiAgentPlanner.PlannerDecisionDto dto = planner.parseDecision(raw);
        assertThat(dto.plan()).isNotNull();
        assertThat(dto.plan().steps()).isEmpty();
        assertThat(dto.plan().cannotFulfill()).isTrue();
    }

    @Test
    @DisplayName("plan 形态的决策 JSON 解析出 plan 分支")
    void shouldParsePlanJson() {
        String raw = """
                {"plan":{"summary":"搜索资源","steps":[{"tool":"search_assets","args":{"keyword":"spring"},"intent":"搜索"}]}}
                """;
        SpringAiAgentPlanner.PlannerDecisionDto dto = planner.parseDecision(raw);
        assertThat(dto.plan()).isNotNull();
        assertThat(dto.plan().summary()).isEqualTo("搜索资源");
    }

    @Test
    @DisplayName("JSON 包在 ```json 围栏里 → 抢救解析成功")
    void shouldSalvageJsonWrappedInCodeFence() {
        String raw = """
                ```json
                {"toolCalls":[{"tool":"search_assets","args":{},"intent":"搜索"}]}
                ```
                """;
        SpringAiAgentPlanner.PlannerDecisionDto dto = planner.parseDecision(raw);
        assertThat(dto.toolCalls()).hasSize(1);
    }

    @Test
    @DisplayName("JSON 前后有叙述文字 → 取最外层大括号抢救成功")
    void shouldSalvageJsonWithSurroundingProse() {
        String raw = "好的，我先搜索相关资源：\n"
                + "{\"toolCalls\":[{\"tool\":\"search_assets\",\"args\":{},\"intent\":\"搜索\"}]}\n"
                + "以上是我的决策。";
        SpringAiAgentPlanner.PlannerDecisionDto dto = planner.parseDecision(raw);
        assertThat(dto.toolCalls()).hasSize(1);
    }

    @Test
    @DisplayName("裸字符串（如 \"plan\"）不是决策 JSON → 抛可读的业务异常而非 Jackson 堆栈")
    void shouldThrowReadableErrorForBareString() {
        // 模型只回了一个 "plan" 的情形
        assertThatThrownBy(() -> planner.parseDecision("\"plan\""))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("decision JSON");
        // 且消息里不包含 Jackson 类名，即不把内部堆栈透给用户
        try {
            planner.parseDecision("\"plan\"");
        } catch (BizException e) {
            assertThat(e.getMessage()).doesNotContain("MismatchedInputException");
        }
    }

    @Test
    @DisplayName("空输出 → 抛业务异常")
    void shouldThrowForEmptyOutput() {
        assertThatThrownBy(() -> planner.parseDecision("  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> planner.parseDecision(null))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("大括号内不是合法 JSON → 抢救失败同样抛可读异常")
    void shouldThrowWhenSalvageAlsoFails() {
        assertThatThrownBy(() -> planner.parseDecision("我觉得应该 {继续观察} 一下"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("decision JSON");
    }

    @Test
    @DisplayName("推理文字里先出现一对花括号 → 取最后一个完整顶层对象，仍能解析（回归：曾用首 { 到末 } 会一起截进来）")
    void shouldSalvageWhenProseContainsBraces() {
        String raw = "我先确认一下 {目标} 是什么，然后给出决策：\n"
                + "{\"toolCalls\":[{\"tool\":\"search_assets\",\"args\":{\"keyword\":\"x\"},\"intent\":\"搜索\"}]}";
        SpringAiAgentPlanner.PlannerDecisionDto dto = planner.parseDecision(raw);
        assertThat(dto.toolCalls()).hasSize(1);
        assertThat(dto.toolCalls().get(0).tool()).isEqualTo("search_assets");
    }

    @Test
    @DisplayName("容忍尾随逗号：模型在对象/数组末尾多写逗号也能解析")
    void shouldParseWithTrailingComma() {
        String raw = "{\"toolCalls\":[{\"tool\":\"search_assets\",\"args\":{},\"intent\":\"搜索\"}],}";
        SpringAiAgentPlanner.PlannerDecisionDto dto = planner.parseDecision(raw);
        assertThat(dto.toolCalls()).hasSize(1);
    }

    @Test
    @DisplayName("输出被截断（花括号未闭合）→ 抛可读异常，绝不误报为成功")
    void shouldThrowOnTruncatedJson() {
        // 模拟真实故障：模型在 readme 字段中途被截断，JSON 没有闭合
        String raw = "{\"toolCalls\":[{\"tool\":\"analyze_upload_batch\",\"args\":{\"skills\":"
                + "[{\"name\":\"a11y-rules\",\"readme\":\"# 标题\\n\\n正文";
        assertThatThrownBy(() -> planner.parseDecision(raw))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("decision JSON");
    }
}
