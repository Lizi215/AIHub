package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.agent.guard.TokenBudgetGuard;
import com.aihub.ai.agent.plan.AgentPlan;
import com.aihub.ai.agent.plan.AgentPlanningState;
import com.aihub.ai.agent.plan.AgentPlanStep;
import com.aihub.ai.agent.plan.AgentStepRecord;
import com.aihub.ai.agent.plan.PlannerDecision;
import com.aihub.ai.enums.AgentScene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无 API Key 的脚本化规划器（docs/AGENT.md §7「无 Key 可运行」）。
 *
 * <p>它不是模型，也不假装是模型：按场景执行固定脚本，从用户请求里抽出资源 id、关键字，
 * 先收集再产出计划。价值是让「搜索 → 生成计划 → 用户确认 → 执行 → 汇总」整条链路在没有 Key
 * 的机器上可被真实验证；真实语义理解由 {@link SpringAiAgentPlanner} 负责。
 *
 * <p>不做自然语言理解（只做规则抽取：引号内为关键字，{@code id: 12345} 为资源 id），
 * 不做多轮对话改写，不生成自由文本结论（汇总由步骤执行记录拼装）。
 */
@Component
public class MockAgentPlanner implements AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(MockAgentPlanner.class);

    /** 资源 id 抽取：支持 `id: 1234567890`、`资源ID=1234567890`、裸的 5 位以上数字 */
    private static final Pattern ASSET_ID_PATTERN = Pattern.compile(
            "(?i)(?:asset\\s*id|资源\\s*id|id)\\s*[:=#]?\\s*(\\d{5,})|(?<![\\d.])(\\d{6,})(?![\\d.])");

    /** 关键字抽取：中英文引号 / 「」里的内容优先 */
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\"'「“]([^\"'」”]{1,50})[\"'」”]");

    private static final int MAX_KEYWORD_CHARS = 50;
    private static final int MAX_WORKSPACE_ASSETS = 3;

    @Override
    public String mode() {
        return "mock";
    }

    @Override
    public PlannerDecision next(AgentPlanningState state) {
        RequestParams params = RequestParams.parse(state.userRequest());
        int tokens = TokenBudgetGuard.estimate(state.userRequest());

        // 「给资源加标签」是写操作（需确认），mock 也走同一套门禁：计划里只提出这一步，确认后才执行
        List<String> tags = parseTags(state.userRequest());
        if (tags.isEmpty() && wantsTags(state.userRequest())) {
            // mock 读不懂资源内容，无法自己分析标签，如实说明而不是编几个标签或空转到超时
            return PlannerDecision.finish(new AgentPlan(
                    "无法完成：mock 模式无法读取资源内容来推断标签，请显式给出标签"
                            + "（例如：给我的所有资源打标签：ai、spring）。"
                            + "配置 API Key 后，模型可以读你的资源再自己判断。",
                    List.of()), tokens);
        }
        if (!tags.isEmpty()) {
            Map<String, Object> tagArgs = new LinkedHashMap<>();
            if (params.assetId() != null) {
                tagArgs.put("assetIds", List.of(params.assetId()));
            } else {
                tagArgs.put("allMine", Boolean.TRUE);
            }
            tagArgs.put("tags", tags);
            tagArgs.put("mode", state.userRequest().contains("改成") ? "REPLACE" : "APPEND");
            return PlannerDecision.finish(new AgentPlan(
                    "为" + (params.assetId() != null ? "指定资源" : "你的全部资源") + "打标签：" + String.join("、", tags),
                    List.of(step("update_asset_tags", tagArgs, "修改资源标签（需你确认后执行）"))), tokens);
        }

        if (state.scene() == AgentScene.SEARCH) {
            return PlannerDecision.finish(searchPlan(params), tokens);
        }
        if (state.scene() == AgentScene.UPLOAD) {
            return PlannerDecision.finish(uploadPlan(params), tokens);
        }

        // 其余场景都要先知道操作哪个资源，且需读过详情才能决定做什么，这一轮轮收集即「选择 Tool → 调用 Tool → 分析结果」
        Long assetId = params.assetId() != null ? params.assetId() : firstAssetId(state.observations());
        if (assetId == null) {
            if (!hasExecuted(state, "search_assets")) {
                return PlannerDecision.gather(List.of(searchStep(params)), tokens);
            }
            // 搜过但没有命中：如实说明，而不是编一个 id 继续跑
            log.info("mock planner found no target asset, scene={}", state.scene());
            return PlannerDecision.finish(new AgentPlan(
                    "没有找到匹配的资源，未生成可执行计划（可换一个关键字重试）", List.of()), tokens);
        }
        if (!hasExecuted(state, "get_asset_detail")) {
            // README 是不可信输入，先在这一步读进来并清洗，注入检测要在计划产出前生效
            return PlannerDecision.gather(List.of(
                    step("get_asset_detail", args("assetId", assetId), "获取资源详情与版本信息")), tokens);
        }

        AgentPlan plan = switch (state.scene()) {
            case DOWNLOAD -> downloadPlan(assetId, params);
            case WORKSPACE -> workspacePlan(state, params, assetId);
            case BUG_ANALYSIS -> bugAnalysisPlan(assetId);
            default -> throw new IllegalStateException("unexpected scene: " + state.scene());
        };
        return PlannerDecision.finish(plan, tokens);
    }

    private boolean hasExecuted(AgentPlanningState state, String tool) {
        return state.observations().stream().anyMatch(record -> tool.equals(record.tool()));
    }

    /* ------------------------------------------------------------------ 各场景脚本 */

    private AgentPlan searchPlan(RequestParams params) {
        return new AgentPlan("检索资源并汇总结果",
                List.of(step("search_assets", args("keyword", params.keyword(), "category", params.category()),
                        "按关键字检索资源")));
    }

    private AgentPlan uploadPlan(RequestParams params) {
        // 查询自己的资源/草稿：一次 list_my_assets 即可回答
        if (params.request().matches(".*(草稿|我的资源|没上传|没发布).*")) {
            return new AgentPlan("查询你的资源与草稿",
                    List.of(step("list_my_assets", args(), "按 owner 视角列出全部资源与状态")));
        }
        // 批量约定（前端生成）：request = "批量上传：a；b；c" → 一次批量分析工具调用
        List<String> names = parseBatchNames(params);
        if (names.size() >= 2) {
            List<Map<String, Object>> skills = new ArrayList<>();
            for (String name : names) {
                // mock 无真模型，README 只能给模板骨架（前端会明示"模板生成"）
                String readme = "# " + name + "\n\n（模板草稿：mock 模式无法生成真实内容，"
                        + "配置 API Key 后由模型撰写。建议补充：用途、内容结构、使用方式。）";
                Map<String, Object> skill = new LinkedHashMap<>();
                skill.put("name", name);
                skill.put("category", "SKILL");
                skill.put("summary", "（模板）" + name + " 的资源简介，配置 API Key 后由模型生成。");
                skill.put("readme", readme);
                skills.add(skill);
            }
            return new AgentPlan("批量分析 " + names.size() + " 个上传候选：查重、分类与标签建议",
                    List.of(step("analyze_upload_batch", args("skills", skills),
                            "逐个查重并生成分类、标签与 README 草稿")));
        }
        return new AgentPlan("分析待上传资源：查重、分类建议与标签建议",
                List.of(step("analyze_upload_candidate", args("name", params.displayName(), "summary", null),
                        "查重并给出分类与标签建议")));
    }

    /** 是否表达了「打/加标签」的意图（用于区分「用户给了标签」与「让 AI 自己判断」）。 */
    private boolean wantsTags(String request) {
        return request != null && request.matches(".*(打标签|加标签|添加标签|配置标签|标签).*");
    }

    /** 从请求里解析标签（mock 的朴素规则）：命中「打/加/添加/配置标签」才处理，取冒号后内容或关键词之后的词，最多 5 个。 */
    private List<String> parseTags(String request) {
        String text = request == null ? "" : request.trim();
        if (!wantsTags(text)) {
            return List.of();
        }
        String body = text;
        int colon = Math.max(text.indexOf('：'), text.indexOf(':'));
        if (colon >= 0) {
            body = text.substring(colon + 1);
        } else {
            body = text.replaceAll(".*?(打标签|加标签|添加标签|配置标签|标签)", "");
        }
        List<String> tags = new ArrayList<>();
        for (String token : body.split("[，,、；;\s]+")) {
            String tag = token.trim();
            // 去掉常见动词/语气词，避免把"加上"当成标签
            tag = tag.replaceAll("^(加上|添加|增加|设为|改成|打上|一个|这个)$", "").trim();
            if (!tag.isEmpty() && tag.length() <= 20 && !tags.contains(tag)) {
                tags.add(tag);
            }
            if (tags.size() >= 5) {
                break;
            }
        }
        return tags;
    }

    /** 解析批量约定（与真模型链路共用同一份实现，避免两套解析漂移）。 */
    private List<String> parseBatchNames(RequestParams params) {
        return com.aihub.ai.agent.plan.BatchUploadConvention.parseNames(params.request());
    }

    private AgentPlan downloadPlan(Long assetId, RequestParams params) {
        // 需确认步骤：确认前 Orchestrator 会硬拒绝执行，任务会停在 WAITING_CONFIRM
        List<AgentPlanStep> steps = List.of(
                step("prepare_download", args("assetId", assetId, "version", params.version()),
                        "解析下载目标（" + params.displayName() + "）"));
        return new AgentPlan("准备下载：" + params.displayName(), steps);
    }

    private AgentPlan workspacePlan(AgentPlanningState state, RequestParams params, Long assetId) {
        List<Long> assetIds = new ArrayList<>();
        assetIds.add(assetId);
        for (Long other : allAssetIds(state.observations())) {
            if (assetIds.size() >= MAX_WORKSPACE_ASSETS) {
                break;
            }
            if (!assetIds.contains(other)) {
                assetIds.add(other);
            }
        }
        return new AgentPlan("生成 Workspace 草案：" + params.displayName() + " 等 " + assetIds.size() + " 个资源",
                List.of(step("build_workspace_draft",
                        args("assetIds", assetIds, "title", params.displayName(),
                                "description", "面向「" + params.displayName() + "」场景的组合方案，"
                                        + "包含 " + assetIds.size() + " 个资源及其依赖，可整包下载后按各资源 README 使用"),
                        "展开资源与依赖，生成草案")));
    }

    private AgentPlan bugAnalysisPlan(Long assetId) {
        return new AgentPlan("分析资源已知问题：检索历史 Issue",
                List.of(step("list_issues", args("assetId", assetId), "检索历史 Issue")));
    }

    /* ------------------------------------------------------------------ 工具方法 */

    private AgentPlanStep searchStep(RequestParams params) {
        return step("search_assets", args("keyword", params.keyword(), "category", params.category()),
                "检索候选资源");
    }

    private AgentPlanStep step(String tool, Map<String, Object> args, String intent) {
        // requiresConfirmation 由 Orchestrator 按 Tool 性质覆写，这里恒为 false，不参与判定
        return new AgentPlanStep(tool, args, intent, false);
    }

    private Map<String, Object> args(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    /** 从搜索结果里取第一个资源的 id；取不到返回 null。 */
    private Long firstAssetId(List<AgentStepRecord> observations) {
        List<Long> ids = allAssetIds(observations);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 从所有只读步骤的结构化输出里收集 assetId（data.items[].assetId）。 */
    @SuppressWarnings("unchecked")
    private List<Long> allAssetIds(List<AgentStepRecord> observations) {
        List<Long> ids = new ArrayList<>();
        for (AgentStepRecord record : observations) {
            if (!(record.data() instanceof Map<?, ?> data)) {
                continue;
            }
            Object items = ((Map<String, Object>) data).get("items");
            if (!(items instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> row) {
                    Long id = toLong(((Map<String, Object>) row).get("assetId"));
                    if (id != null && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从用户请求里抽出的参数。
     *
     * <p>规则简单，因此可预测：mock 宁可少做，也不要猜错。
     */
    record RequestParams(String request, String keyword, String category, Long assetId, String version, String displayName) {

        static RequestParams parse(String request) {
            String text = request == null ? "" : request.trim();
            Long assetId = extractAssetId(text);
            String keyword = extractKeyword(text);
            String category = extractCategory(text);
            String version = extractVersion(text);
            String displayName = keyword != null && !keyword.isBlank() ? keyword
                    : (text.length() > MAX_KEYWORD_CHARS ? text.substring(0, MAX_KEYWORD_CHARS) : text);
            return new RequestParams(text, keyword, category, assetId, version, displayName);
        }

        private static Long extractAssetId(String text) {
            Matcher matcher = ASSET_ID_PATTERN.matcher(text);
            if (matcher.find()) {
                String digits = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                try {
                    return Long.parseLong(digits);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private static String extractKeyword(String text) {
            Matcher matcher = KEYWORD_PATTERN.matcher(text);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
            if (text.isBlank()) {
                return null;
            }
            return text.length() > MAX_KEYWORD_CHARS ? text.substring(0, MAX_KEYWORD_CHARS) : text;
        }

        private static String extractCategory(String text) {
            Matcher matcher = Pattern.compile("(?i)\\b(SKILL|HOOK|PROMPT|MCP|RULES|TEMPLATE|OTHER)\\b").matcher(text);
            return matcher.find() ? matcher.group(1).toUpperCase() : null;
        }

        private static String extractVersion(String text) {
            Matcher matcher = Pattern.compile("(?i)\\bv?(\\d+\\.\\d+(?:\\.\\d+)?)\\b").matcher(text);
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}
