package com.aihub.ai.agent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BatchUploadConvention}：前端约定格式的解析与确定性决策构造。
 */
class BatchUploadConventionTest {

    @Test
    @DisplayName("解析约定格式：分号/顿号/逗号/空格分隔，去重且 ≤10")
    void shouldParseNames() {
        assertThat(BatchUploadConvention.parseNames("批量上传：a；b；c")).containsExactly("a", "b", "c");
        assertThat(BatchUploadConvention.parseNames("批量上传: a、b，c d")).containsExactly("a", "b", "c", "d");
        // 去重
        assertThat(BatchUploadConvention.parseNames("批量上传：a；a；b")).containsExactly("a", "b");
        // 非约定格式
        assertThat(BatchUploadConvention.parseNames("帮我上传一个 skill")).isEmpty();
        assertThat(BatchUploadConvention.parseNames(null)).isEmpty();
        // 上限
        assertThat(BatchUploadConvention.parseNames(
                "批量上传：1；2；3；4；5；6；7；8；9；10；11；12")).hasSize(10);
    }

    @Test
    @DisplayName("构造决策：有模型写的简介/README 时用模型的，缺失的用模板兜底")
    void shouldBuildDecisionWithFallback() {
        List<String> names = List.of("study-notes", "save-last");
        Map<String, String[]> copies = Map.of(
                "study-notes", new String[]{"学习笔记整理工具", "# study-notes\n\n用法：……"});

        PlannerDecision decision = BatchUploadConvention.buildDecision(names, copies, null, 120);

        assertThat(decision.plan()).isNotNull();
        assertThat(decision.plan().steps()).hasSize(1);
        AgentPlanStep step = decision.plan().steps().get(0);
        assertThat(step.tool()).isEqualTo("analyze_upload_batch");
        assertThat(decision.tokenUsed()).isEqualTo(120);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) step.args().get("skills");
        assertThat(skills).hasSize(2);
        assertThat(skills.get(0)).containsEntry("name", "study-notes");
        assertThat((String) skills.get(0).get("summary")).isEqualTo("学习笔记整理工具");
        assertThat((String) skills.get(0).get("readme")).contains("用法");
        // save-last 没有模型内容 → 模板兜底，但仍然带上名字
        assertThat((String) skills.get(1).get("readme")).contains("save-last");
    }

    @Test
    @DisplayName("类型声明：｜类型：SKILL 可解析、不污染名字列表、并覆盖每条的 category")
    void shouldParseAndApplyTypeHint() {
        String request = "批量上传：a；b｜类型：SKILL";
        assertThat(BatchUploadConvention.parseNames(request)).containsExactly("a", "b");
        assertThat(BatchUploadConvention.parseTypeHint(request)).isEqualTo("SKILL");

        PlannerDecision decision = BatchUploadConvention.buildDecision(
                BatchUploadConvention.parseNames(request), Map.of(), "SKILL", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills =
                (List<Map<String, Object>>) decision.plan().steps().get(0).args().get("skills");
        assertThat(skills).allSatisfy(skill -> assertThat(skill).containsEntry("category", "SKILL"));
        assertThat(BatchUploadConvention.parseTypeHint("批量上传：a；b")).isNull();
    }

    @Test
    @DisplayName("构造决策：没有类型声明且模型没给分类时兜底 SKILL")
    void shouldDefaultCategoryToSkill() {
        PlannerDecision decision = BatchUploadConvention.buildDecision(List.of("x"), Map.of(), null, 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills =
                (List<Map<String, Object>>) decision.plan().steps().get(0).args().get("skills");
        assertThat(skills.get(0)).containsEntry("category", "SKILL");
    }

    @Test
    @DisplayName("分类优先级：用户声明 > 名字规则 > 模型判断 > SKILL 兜底")
    void shouldApplyCategoryPriority() {
        // 规则命中（-mcp）优先于模型给的 SKILL
        PlannerDecision decision = BatchUploadConvention.buildDecision(
                List.of("filesystem-mcp"), Map.of("filesystem-mcp", new String[]{"简介", "README", "SKILL"}), null, 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills =
                (List<Map<String, Object>>) decision.plan().steps().get(0).args().get("skills");
        assertThat(skills.get(0)).containsEntry("category", "MCP");

        // 用户显式声明优先于规则
        PlannerDecision hinted = BatchUploadConvention.buildDecision(
                List.of("filesystem-mcp"), Map.of(), "SKILL", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hintedSkills =
                (List<Map<String, Object>>) hinted.plan().steps().get(0).args().get("skills");
        assertThat(hintedSkills.get(0)).containsEntry("category", "SKILL");

        // 名字没线索时用模型给的分类
        PlannerDecision fromModel = BatchUploadConvention.buildDecision(
                List.of("demo-combo"), Map.of("demo-combo", new String[]{"简介", "README", "PROMPT"}), null, 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modelSkills =
                (List<Map<String, Object>>) fromModel.plan().steps().get(0).args().get("skills");
        assertThat(modelSkills.get(0)).containsEntry("category", "PROMPT");
    }

    @Test
    @DisplayName("构造决策：copies 为空时不抛异常，全部走模板")
    void shouldBuildDecisionWithoutCopies() {
        PlannerDecision decision = BatchUploadConvention.buildDecision(List.of("x", "y"), Map.of(), null, 0);

        assertThat(decision.plan().steps()).hasSize(1);
        assertThat(decision.plan().steps().get(0).args()).containsKey("skills");
    }
}
