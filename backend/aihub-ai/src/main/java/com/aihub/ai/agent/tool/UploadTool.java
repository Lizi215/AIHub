package com.aihub.ai.agent.tool;

import com.aihub.ai.agent.plan.CategoryInferrer;
import com.aihub.api.asset.AssetQueryApi;
import com.aihub.api.dto.AssetQueryItem;
import com.aihub.common.result.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Upload Tool：上传前的分析与查重（docs/AGENT.md §2）。
 *
 * <p>本工具只做只读分析（查重、分类推断、标签建议），不创建资源、不写库、
 * 不生成 README 正文；真正的上传仍是用户走三阶段上传接口。能力边界要讲清楚，
 * 否则模型会声称「已上传」。
 *
 * <p>也不做文件魔数校验：那需要文件字节，而 Agent 手里没有文件，魔数校验是
 * File Service 的职责（docs/SECURITY.md §3）。
 */
@Component
public class UploadTool implements AgentToolBean {

    /** 从名称里抽标签时忽略的词 */
    private static final Set<String> TAG_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "of", "to", "in", "on", "a", "an",
            "v", "version", "skill", "hook", "prompt", "mcp", "rules", "template");

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{Alnum}\\u4e00-\\u9fa5]+");

    private final AssetQueryApi assetQueryApi;

    public UploadTool(AssetQueryApi assetQueryApi) {
        this.assetQueryApi = assetQueryApi;
    }

    public record AnalyzeUploadCandidateArgs(
            @ToolParam(description = "计划上传的资源名称")
            @NotBlank(message = "name is required")
            @Size(max = 100, message = "name must be at most 100 characters")
            String name,

            @ToolParam(required = false, description = "资源简介，可为空。用于提取标签建议")
            @Size(max = 300, message = "summary must be at most 300 characters")
            String summary
    ) {
    }

    @Tool(name = "analyze_upload_candidate",
            description = """
                    上传前分析：检查是否已有同名资源（查重）、推荐分类、给出标签建议（标签由你撰写）。
                    用途：用户准备上传**单个**资源并询问「有没有重复」「该选什么分类」时使用。
                    参数：name 资源名称（必填）；summary 资源简介（可空，用于提取标签）。
                    返回：{exactNameDuplicate, duplicates:[{assetId, name, latestVersion}], inferredCategory,
                          suggestedTags:[...], notes:[...]}。
                    不使用场景：本工具不创建资源、不保存任何数据、也不生成 README 正文——
                    不要在返回里声称「已上传」或「已创建草稿」，上传动作始终由用户自己完成。
                    批量上传多个资源时改用 analyze_upload_batch，一次调用传全部候选。
                    """)
    public ToolResult analyzeUploadCandidate(AnalyzeUploadCandidateArgs args) {
        NameAnalysis analysis = analyzeName(args.name(), args.summary());
        List<String> notes = new ArrayList<>();
        if (analysis.exactDuplicate()) {
            notes.add("你已发布过同名资源，建议改名或直接在原资源上加版本");
        } else if (analysis.otherAuthorSameName()) {
            notes.add("其他作者有同名资源（不同作者同名是正常的，不构成冲突）");
        } else if (!analysis.duplicates().isEmpty()) {
            notes.add("存在名称相近的已发布资源，请确认是否为重复上传");
        }
        notes.add("本次分析只读，未创建任何资源");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exactNameDuplicate", analysis.exactDuplicate());
        data.put("duplicates", analysis.duplicates());
        data.put("inferredCategory", analysis.inferredCategory());
        data.put("suggestedTags", analysis.suggestedTags());
        data.put("notes", notes);

        String message = analysis.exactDuplicate()
                ? "已存在同名资源，建议确认后再上传"
                : "未发现同名资源，可以上传（另有 " + analysis.duplicates().size() + " 条名称相近的资源）";
        return ToolResult.success(message, data);
    }

    /** 批量参数里的单个候选：name 必填，分类/简介/README 草稿由模型撰写，工具只透传。 */
    public record BatchSkillArgs(
            @ToolParam(description = "资源名称")
            @NotBlank(message = "name must not be blank")
            @Size(max = 100, message = "name must be at most 100 characters")
            String name,

            @ToolParam(required = false, description = "分类建议（SKILL/HOOK/PROMPT/MCP/RULES/TEMPLATE/OTHER 之一），由你根据资源名判断")
            @Size(max = 30, message = "category must be at most 30 characters")
            String category,

            @ToolParam(required = false, description = "一句话资源简介，由你撰写（用户确认后随资源落库展示）")
            @Size(max = 300, message = "summary must be at most 300 characters")
            String summary,

            @ToolParam(required = false, description = "该资源的 README 草稿（Markdown）：用途、内容结构、使用方式。由你撰写，用户确认后随上传落库")
            @Size(max = 2000, message = "readme must be at most 2000 characters")
            String readme,

            @ToolParam(required = false, description = "标签建议（1-3 个，每个不超过 20 字符）。由你基于名称与简介撰写；不填则系统按关键词规则兜底生成")
            @Size(max = 5, message = "suggestedTags must have at most 5 entries")
            List<String> suggestedTags
    ) {
    }

    public record AnalyzeUploadBatchArgs(
            @ToolParam(description = "待分析的候选列表，1-10 条")
            @NotEmpty(message = "skills must not be empty")
            @Size(max = 10, message = "skills must contain at most 10 items")
            List<@NotNull(message = "skill must not be null") @Valid BatchSkillArgs> skills
    ) {
    }

    @Tool(name = "analyze_upload_batch",
            description = """
                    批量上传前分析：对一组待上传资源逐个查重、推荐分类、给出标签建议（标签由你撰写）。
                    用途：用户要求「批量上传多个资源」时使用，一次调用传入全部候选，不要逐个调 analyze_upload_candidate。
                    参数：skills 列表（必填，1-10 条），每条 {name: 资源名（必填）,
                          category: 分类建议（SKILL/HOOK/PROMPT/MCP/RULES/TEMPLATE/OTHER 之一，由你判断）,
                          summary: 一句话简介（由你撰写）,
                          suggestedTags: 标签建议 1-3 个（由你基于名称与简介撰写）,
                          readme: README 草稿（Markdown，由你撰写：这个资源是做什么的、内容结构、怎么用；
                          基于资源名与你已知的观察结果来写，不要编造不存在的功能）}。
                    分类规则：用户明确说明了资源类型（如「这些都是 skill」）时，category 必须采用用户指定的类型，
                    不要自作主张改成别的；用户没说才由你判断。
                    同名规则：exactNameDuplicate 只在「与**当前用户自己**已发布资源同名」时才为真
                    （此时会带上 sameNameAssetId / sameNameLatestVersion / sameNameStatus，前端据此**追加新版本**而不是新建重复资源，并按状态如实提示草稿/已发布）；
                    其他作者的同名资源属于正常现象（otherAuthorSameName），不要提示冲突、不要建议改名。
                    返回：{suggestions:[{name, category, summary, readme, readmeSource, exactNameDuplicate, otherAuthorSameName, batchDuplicate,
                          inferredCategory, suggestedTags, notes}]}——readme 原样透传并标注来源，
                          最终是否采用由用户在前端确认，本工具不创建任何资源。
                    """)
    public ToolResult analyzeUploadBatch(AnalyzeUploadBatchArgs args) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        int duplicatesInBatch = 0;
        for (BatchSkillArgs skill : args.skills()) {
            String name = skill.name().trim();
            NameAnalysis analysis = analyzeName(name, null);
            // 模型指定的分类优先；没有再走关键词推断
            String category = skill.category() != null && !skill.category().isBlank()
                    ? skill.category().trim() : analysis.inferredCategory();
            String summary = skill.summary() == null || skill.summary().isBlank() ? null : skill.summary();
            boolean batchDuplicate = !seen.add(name.toLowerCase(Locale.ROOT));
            if (batchDuplicate) {
                duplicatesInBatch++;
            }
            String readme = skill.readme() == null || skill.readme().isBlank() ? null : skill.readme();

            List<String> notes = new ArrayList<>();
            if (analysis.exactDuplicate()) {
                notes.add("你已发布过同名资源，建议改名");
            }
            if (analysis.otherAuthorSameName()) {
                notes.add("其他作者有同名资源（不构成冲突）");
            }
            if (batchDuplicate) {
                notes.add("本批次内重名，后者会被前端跳过");
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("category", category);
            row.put("summary", summary);
            row.put("readme", readme);
            row.put("readmeSource", readme == null ? "none" : "ai-draft");
            // 标签：模型基于内容撰写的优先；没给时退回关键词启发式（mock 模式的常态）
            List<String> suggestedTags = sanitizeTags(skill.suggestedTags());
            if (suggestedTags.isEmpty()) {
                suggestedTags = suggestTags(name, summary);
            }
            row.put("suggestedTags", suggestedTags);
            row.put("suggestedTagsSource", skill.suggestedTags() != null && !skill.suggestedTags().isEmpty()
                    ? "ai" : "keyword");
            row.put("exactNameDuplicate", analysis.exactDuplicate());
            row.put("otherAuthorSameName", analysis.otherAuthorSameName());
            // 自己已有同名资源时给出目标 id，前端「上传并发布」会直接给它追加新版本，而不是造重复资源
            row.put("sameNameAssetId", analysis.ownSameNameAssetId());
            row.put("sameNameLatestVersion", analysis.ownSameNameVersion());
            // 目标资源状态（DRAFT / PUBLISHED / SUSPENDED），前端据此区分「已有同名草稿」与「已发布过同名资源」
            row.put("sameNameStatus", analysis.ownSameNameStatus());
            row.put("batchDuplicate", batchDuplicate);
            row.put("inferredCategory", analysis.inferredCategory());
            row.put("batchDuplicate", batchDuplicate);
            row.put("notes", notes);
            suggestions.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("suggestions", suggestions);
        data.put("notes", List.of("本次分析只读，未创建任何资源；README 草稿由 AI 生成，采用与否由用户确认"));

        String message = "批量分析完成：" + suggestions.size() + " 个候选"
                + (duplicatesInBatch > 0 ? "，批内重名 " + duplicatesInBatch + " 个" : "");
        return ToolResult.success(message, data);
    }

    /** 单个名称的查重 + 分类 + 标签分析（单条与批量工具共用）。 */
    private NameAnalysis analyzeName(String name, String summary) {
        PageResult<AssetQueryItem> existing = RemoteResults.unwrap(
                assetQueryApi.search(name, null, null, 1, 10), "analyze upload candidate");
        List<Map<String, Object>> duplicates = new ArrayList<>();
        Long currentUserId = com.aihub.common.trace.UserContext.getUserId();
        boolean exactDuplicate = false;
        boolean otherAuthorSameName = false;
        Long ownSameNameAssetId = null;
        String ownSameNameVersion = null;
        String ownSameNameStatus = null;
        for (AssetQueryItem item : existing.records()) {
            boolean sameName = item.name() != null && item.name().equalsIgnoreCase(name.trim());
            if (sameName) {
                // 同名是否算「重复」取决于作者：自己的才是冲突，别人同名是正常现象
                if (currentUserId != null && currentUserId.equals(item.ownerId())) {
                    exactDuplicate = true;
                    // 带上自己同名资源的 id/版本/状态，前端可直接追加新版本，而不是再建重复资源
                    ownSameNameAssetId = item.id();
                    ownSameNameVersion = item.latestVersion();
                    ownSameNameStatus = item.status();
                } else {
                    otherAuthorSameName = true;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assetId", item.id());
            row.put("name", item.name());
            row.put("latestVersion", item.latestVersion());
            row.put("ownerId", item.ownerId());
            row.put("sameOwner", currentUserId != null && currentUserId.equals(item.ownerId()));
            duplicates.add(row);
        }
        // search（ES）只含已发布资源，自己的草稿也要算同名，否则批量上传同一个资源两次会造出两个重复草稿；mine() 覆盖全部状态
        Long userId = com.aihub.common.trace.UserContext.getUserId();
        if (userId != null && ownSameNameAssetId == null) {
            for (AssetQueryItem item : RemoteResults.unwrap(assetQueryApi.mine(userId), "analyze own assets")) {
                if (item.name() != null && item.name().equalsIgnoreCase(name.trim())) {
                    exactDuplicate = true;
                    ownSameNameAssetId = item.id();
                    ownSameNameVersion = item.latestVersion();
                    ownSameNameStatus = item.status();
                    break;
                }
            }
        }
        return new NameAnalysis(exactDuplicate, otherAuthorSameName, ownSameNameAssetId, ownSameNameVersion,
                ownSameNameStatus, duplicates, inferCategory(name, summary), suggestTags(name, summary));
    }

    private record NameAnalysis(boolean exactDuplicate, boolean otherAuthorSameName,
                                Long ownSameNameAssetId, String ownSameNameVersion, String ownSameNameStatus,
                                List<Map<String, Object>> duplicates,
                                String inferredCategory, List<String> suggestedTags) {
    }

    /** 标签清洗：去空白（含全角）、去重、限长限数；清洗后为空则返回空列表（由调用方兜底）。 */
    private List<String> sanitizeTags(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : raw) {
            if (tag == null) {
                continue;
            }
            String t = tag.strip();
            if (t.isEmpty() || t.length() > 20 || result.contains(t)) {
                continue;
            }
            result.add(t);
            if (result.size() >= 3) {
                break;
            }
        }
        return result;
    }

    /** 分类推断：与批量通道共用同一套规则（{@link CategoryInferrer}），避免两处漂移。 */
    private String inferCategory(String name, String summary) {
        return CategoryInferrer.infer((name == null ? "" : name) + " " + (summary == null ? "" : summary));
    }

    private List<String> suggestTags(String name, String summary) {
        String haystack = ((name == null ? "" : name) + " " + (summary == null ? "" : summary))
                .toLowerCase(Locale.ROOT);
        Set<String> tags = new LinkedHashSet<>();
        for (String token : TOKEN_SPLITTER.split(haystack)) {
            if (token.isBlank() || token.length() < 2 || token.length() > 20) {
                continue;
            }
            if (TAG_STOP_WORDS.contains(token)) {
                continue;
            }
            tags.add(token);
            if (tags.size() >= 5) {
                break;
            }
        }
        return List.copyOf(tags);
    }
}
