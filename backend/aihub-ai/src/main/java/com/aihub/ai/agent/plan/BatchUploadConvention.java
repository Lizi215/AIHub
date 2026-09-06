package com.aihub.ai.agent.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量上传的请求约定（前端生成，格式固定）：{@code 批量上传：a；b；c}。
 *
 * <p>格式可选带类型声明：{@code 批量上传：a；b｜类型：SKILL}（前端在用户描述里明确
 * 说了类型时附加，保证「我说了这些都是 skill」能真正生效）。
 *
 * <p>服务端确定性识别它，而不是交给模型理解：面对这种结构化约定，真模型会返回零步骤
 * 计划而不去调用 {@code analyze_upload_batch}。识别约定后直接构造工具调用，
 * 模型只负责写简介与 README 草稿。
 */
public final class BatchUploadConvention {

    /** 与 UploadTool 的 @Size(max=10) 对齐 */
    public static final int MAX_SKILLS = 10;

    private BatchUploadConvention() {
    }

    /** @return 约定格式里的名字列表（去重、≤10）；不是批量约定则返回空列表 */
    public static List<String> parseNames(String request) {
        if (request == null || !request.trim().startsWith("批量上传")) {
            return List.of();
        }
        String body = stripTypeHint(request.trim()).replaceFirst("^批量上传[:：]?", "");
        List<String> names = new ArrayList<>();
        for (String token : body.split("[；;、,，\\s]+")) {
            String name = token.trim();
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
            if (names.size() >= MAX_SKILLS) {
                break;
            }
        }
        return names;
    }

    /** @return 用户在描述里声明的资源类型（如 SKILL）；没声明返回 null */
    public static String parseTypeHint(String request) {
        if (request == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("｜类型[:：]\s*([A-Za-z]+)").matcher(request);
        return m.find() ? m.group(1).toUpperCase(java.util.Locale.ROOT) : null;
    }

    private static String stripTypeHint(String request) {
        return request.replaceAll("｜类型[:：]\s*[A-Za-z]+", "");
    }

    /**
     * 构造批量分析的工具调用（确定性，不经模型决策）。
     *
     * @param copies     name → [summary, readme, category, tags(、拼接)]，由模型撰写；缺失的用模板/类型声明兜底
     * @param typeHint   用户在描述里声明的统一类型（可空）
     */
    public static PlannerDecision buildDecision(List<String> names, Map<String, String[]> copies,
                                               String typeHint, int tokens) {
        List<Map<String, Object>> skills = new ArrayList<>();
        for (String name : names) {
            String[] copy = copies == null ? null : copies.get(name);
            String summary = copy != null && copy.length > 0 && copy[0] != null && !copy[0].isBlank()
                    ? copy[0] : "（待补充）" + name + " 的资源简介。";
            String readme = copy != null && copy.length > 1 && copy[1] != null && !copy[1].isBlank()
                    ? copy[1] : templateReadme(name);
            // 分类优先级：用户声明的类型 > 名字规则（确定性）> 模型判断 > SKILL 兜底。
            // 规则优先于模型，因为分类有客观线索，模型会一律给同一个值
            String category = typeHint;
            if (category == null) {
                category = CategoryInferrer.infer(name);
            }
            if (category == null && copy != null && copy.length > 2 && copy[2] != null && !copy[2].isBlank()) {
                category = copy[2].trim().toUpperCase(java.util.Locale.ROOT);
            }
            if (category == null) {
                category = "SKILL";
            }
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("name", name);
            skill.put("category", category);
            skill.put("summary", summary);
            skill.put("readme", readme);
            // 模型写的标签建议（"、" 拆回列表）；没给就不设，工具侧会用关键词启发式兜底
            if (copy != null && copy.length > 3 && copy[3] != null && !copy[3].isBlank()) {
                skill.put("suggestedTags", java.util.Arrays.stream(copy[3].split("、"))
                        .map(String::strip).filter(t -> !t.isEmpty()).toList());
            }
            skills.add(skill);
        }
        AgentPlanStep step = new AgentPlanStep("analyze_upload_batch",
                Map.of("skills", skills), "逐个查重并生成分类、标签与 README 草稿", false);
        return PlannerDecision.finish(
                new AgentPlan("批量分析 " + names.size() + " 个上传候选：查重、分类与标签建议",
                        List.of(step)), tokens);
    }

    private static String templateReadme(String name) {
        return "# " + name + "\n\n（模板草稿：未能生成个性化内容，建议补充：用途、内容结构、使用方式。）";
    }
}
