package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.agent.plan.AgentPlan;
import com.aihub.ai.agent.plan.AgentPlanningState;
import com.aihub.ai.agent.plan.AgentPlanStep;
import com.aihub.ai.agent.plan.AgentStepRecord;
import com.aihub.ai.agent.plan.PlannerDecision;
import com.aihub.ai.enums.AgentScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mock 规划器。它是「无 API Key 也能完整演示」的关键一环（docs/AGENT.md §7），
 * 所以脚本必须确定且可预测：同样的输入永远产同样的计划。
 */
class MockAgentPlannerTest {

    private static final long ASSET_ID = 1234567890L;

    private final MockAgentPlanner planner = new MockAgentPlanner();

    @Test
    @DisplayName("模式标识为 mock：任务里要能看出这次不是真实模型产出的")
    void modeShouldBeMock() {
        assertThat(planner.mode()).isEqualTo("mock");
    }

    @Test
    @DisplayName("搜索场景：直接产出检索计划，不需要先收集")
    void searchSceneShouldPlanDirectly() {
        PlannerDecision decision = planner.next(state(AgentScene.SEARCH, "帮我找 \"spring cloud skill\"", List.of()));

        assertThat(decision.plan()).isNotNull();
        assertThat(decision.plan().steps()).hasSize(1);
        AgentPlanStep step = decision.plan().steps().get(0);
        assertThat(step.tool()).isEqualTo("search_assets");
        assertThat(step.args()).containsEntry("keyword", "spring cloud skill");
        assertThat(decision.plan().requiresConfirmation()).isFalse();
    }

    @Test
    @DisplayName("上传场景：产出上传前分析计划（只读，不落库）")
    void uploadSceneShouldPlanAnalysis() {
        PlannerDecision decision = planner.next(state(AgentScene.UPLOAD, "我要上传 \"My Skill\"", List.of()));

        assertThat(decision.plan().steps()).hasSize(1);
        assertThat(decision.plan().steps().get(0).tool()).isEqualTo("analyze_upload_candidate");
        assertThat(decision.plan().steps().get(0).args()).containsEntry("name", "My Skill");
    }

    @Test
    @DisplayName("打标签指令：走 update_asset_tags（写操作，需确认），并解析出标签")
    void tagRequestShouldPlanTagUpdate() {
        PlannerDecision decision = planner.next(
                state(AgentScene.SEARCH, "给我的所有资源打标签：ai, spring", List.of()));

        assertThat(decision.plan().steps()).hasSize(1);
        assertThat(decision.plan().steps().get(0).tool()).isEqualTo("update_asset_tags");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) decision.plan().steps().get(0).args().get("tags");
        assertThat(tags).containsExactly("ai", "spring");
        assertThat(decision.plan().steps().get(0).args()).containsEntry("allMine", Boolean.TRUE);
    }

    @Test
    @DisplayName("只说打标签、不给标签：mock 如实说明无法推断，不编造标签也不空转")
    void tagRequestWithoutTagsShouldDecline() {
        PlannerDecision decision = planner.next(
                state(AgentScene.SEARCH, "给我的所有资源打标签，你自己分析适合什么标签", List.of()));

        assertThat(decision.plan()).isNotNull();
        assertThat(decision.plan().steps()).isEmpty();
        assertThat(decision.plan().summary()).contains("无法完成").contains("请显式给出标签");
    }

    @Test
    @DisplayName("查询草稿/我的资源：走 list_my_assets（owner 视角，含草稿）")
    void myAssetsQueryShouldPlanListMine() {
        PlannerDecision decision = planner.next(state(AgentScene.UPLOAD, "查找一下我还有哪些草稿没有上传的", List.of()));

        assertThat(decision.plan().steps()).hasSize(1);
        assertThat(decision.plan().steps().get(0).tool()).isEqualTo("list_my_assets");
    }

    @Test
    @DisplayName("上传场景批量约定：request 以「批量上传：a；b；c」开头时走批量工具")
    void uploadSceneBatchShouldPlanBatchAnalysis() {        PlannerDecision decision = planner.next(state(AgentScene.UPLOAD, "批量上传：skill-a；skill-b；skill-c", List.of()));

        assertThat(decision.plan().steps()).hasSize(1);
        assertThat(decision.plan().steps().get(0).tool()).isEqualTo("analyze_upload_batch");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills =
                (List<Map<String, Object>>) decision.plan().steps().get(0).args().get("skills");
        assertThat(skills).hasSize(3);
        assertThat(skills.get(0)).containsEntry("name", "skill-a");
        assertThat((String) skills.get(0).get("readme")).contains("skill-a");
    }

    @Test
    @DisplayName("下载场景：不知道资源 id 时先检索，而不是编一个 id")
    void downloadSceneShouldGatherFirst() {
        PlannerDecision decision = planner.next(state(AgentScene.DOWNLOAD, "下载 \"spring skill\"", List.of()));

        assertThat(decision.plan()).isNull();
        assertThat(decision.toolCalls()).hasSize(1);
        assertThat(decision.toolCalls().get(0).tool()).isEqualTo("search_assets");
    }

    @Test
    @DisplayName("下载场景：拿到搜索结果后先读详情，再产出计划（分两轮收集）")
    void downloadSceneShouldPlanAfterObservation() {
        List<AgentStepRecord> searched = List.of(searchObservation(ASSET_ID));

        PlannerDecision afterSearch = planner.next(state(AgentScene.DOWNLOAD, "下载 \"spring skill\"", searched));
        assertThat(afterSearch.plan()).isNull();
        assertThat(afterSearch.toolCalls()).extracting(AgentPlanStep::tool).containsExactly("get_asset_detail");
        assertThat(afterSearch.toolCalls().get(0).args()).containsEntry("assetId", ASSET_ID);

        List<AgentStepRecord> withDetail = List.of(searchObservation(ASSET_ID), detailObservation(ASSET_ID));
        PlannerDecision decision = planner.next(state(AgentScene.DOWNLOAD, "下载 \"spring skill\"", withDetail));

        AgentPlan plan = decision.plan();
        assertThat(plan).isNotNull();
        assertThat(plan.steps()).extracting(AgentPlanStep::tool).containsExactly("prepare_download");
        assertThat(plan.steps().get(0).args()).containsEntry("assetId", ASSET_ID);
        // 需确认标记由 Orchestrator 按 Tool 性质覆写，规划器给的值不作数
        assertThat(plan.steps().get(0).requiresConfirmation()).isFalse();
    }

    @Test
    @DisplayName("下载场景：请求里直接给了 id 也要先读详情，避免在没看过 README 的情况下就提出下载")
    void downloadSceneShouldAlwaysReadDetailFirst() {
        PlannerDecision decision = planner.next(
                state(AgentScene.DOWNLOAD, "下载 asset id: " + ASSET_ID, List.of()));

        assertThat(decision.plan()).isNull();
        assertThat(decision.toolCalls()).extracting(AgentPlanStep::tool).containsExactly("get_asset_detail");
    }

    @Test
    @DisplayName("搜不到资源：产出空计划并说明原因，而不是编造一个不存在的资源")
    void shouldProduceEmptyPlanWhenNothingFound() {
        PlannerDecision decision = planner.next(
                state(AgentScene.DOWNLOAD, "下载 \"不存在的资源\"", List.of(emptySearchObservation())));

        assertThat(decision.plan()).isNotNull();
        assertThat(decision.plan().steps()).isEmpty();
        assertThat(decision.plan().summary()).contains("没有找到匹配的资源");
    }

    @Test
    @DisplayName("Workspace 场景：把命中资源一起纳入草案（上限 3 个）")
    void workspaceSceneShouldCollectAssets() {
        List<AgentStepRecord> observations = List.of(
                searchObservation(ASSET_ID, 999L, 888L), detailObservation(ASSET_ID));

        PlannerDecision decision = planner.next(state(AgentScene.WORKSPACE, "组合 \"spring\"", observations));

        AgentPlanStep step = decision.plan().steps().get(0);
        assertThat(step.tool()).isEqualTo("build_workspace_draft");
        assertThat(step.args().get("assetIds")).isEqualTo(List.of(ASSET_ID, 999L, 888L));
    }

    @Test
    @DisplayName("Bug 分析场景：读过详情后检索历史 Issue")
    void bugAnalysisSceneShouldFetchIssues() {
        List<AgentStepRecord> observations = List.of(
                searchObservation(ASSET_ID), detailObservation(ASSET_ID));

        PlannerDecision decision = planner.next(
                state(AgentScene.BUG_ANALYSIS, "分析 \"spring\" 的问题", observations));

        assertThat(decision.plan().steps()).extracting(AgentPlanStep::tool)
                .containsExactly("list_issues");
    }

    /* ------------------------------------------------------------------ 构造 */

    private AgentPlanningState state(AgentScene scene, String request, List<AgentStepRecord> observations) {
        return new AgentPlanningState(scene, request, observations, observations.size(), List.of());
    }

    private AgentStepRecord detailObservation(Long assetId) {
        return AgentStepRecord.succeeded("get_asset_detail", Map.of("assetId", assetId), "获取资源详情",
                "资源详情已获取", Map.of("assetId", assetId, "latestVersion", "1.0.0"), List.of(), 5L, false);
    }

    @SafeVarargs
    private AgentStepRecord searchObservation(Long... assetIds) {
        List<Map<String, Object>> items = java.util.Arrays.stream(assetIds)
                .map(id -> Map.<String, Object>of("assetId", id, "name", "asset-" + id))
                .toList();
        return AgentStepRecord.succeeded("search_assets", Map.of("keyword", "spring"), "检索候选资源",
                "找到 " + items.size() + " 条资源",
                Map.of("total", items.size(), "items", items), List.of(), 5L, false);
    }

    private AgentStepRecord emptySearchObservation() {
        return AgentStepRecord.succeeded("search_assets", Map.of("keyword", "none"), "检索候选资源",
                "没有找到匹配的资源", Map.of("total", 0, "items", List.of()), List.of(), 5L, false);
    }
}
