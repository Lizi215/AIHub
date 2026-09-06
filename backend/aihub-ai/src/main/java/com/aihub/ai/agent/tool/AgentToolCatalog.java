package com.aihub.ai.agent.tool;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tool 注册表：把 Java 方法变成「可被 Orchestrator 调度的一件事」。
 *
 * <p>Tool 定义只写一份，即标注了 Spring AI {@link Tool} 的 Bean 方法。真实模型链路把同一批
 * Bean 交给 Spring AI（{@link ToolCallbacks#from}）由框架驱动循环；mock 链路与确认后的执行
 * 阶段则由本类按名字直接调度，两条路径不存在定义漂移。性质在 {@link #NATURES} 里显式登记，
 * 启动时双向校验（漏登记或登记了不存在的名字都会失败），避免漏登记把确认门禁悄悄关掉。
 * 参数一律先反序列化、再走 Jakarta Validation，最后才调用方法，因为模型参数不可信任。
 */
@Component
public class AgentToolCatalog {

    /**
     * Tool 性质登记表。新增 Tool 必须在这里登记，否则应用启动失败。
     *
     * <p>不写在方法注解上，是因为注解漏写不报错，只会让本该需要确认的工具变成直接可执行；
     * 这里漏写会直接启动失败。
     */
    private static final Map<String, AgentToolNature> NATURES = Map.of(
            // 只读检索与分析
            "search_assets", AgentToolNature.READ,
            "get_asset_detail", AgentToolNature.READ,
            "list_issues", AgentToolNature.READ,
            "list_posts", AgentToolNature.READ,
            "analyze_upload_candidate", AgentToolNature.READ,
            "analyze_upload_batch", AgentToolNature.READ,
            "list_my_assets", AgentToolNature.READ,
            "build_workspace_draft", AgentToolNature.READ,
            // 下载会产生真实后果（带宽、下载记录、热门榜），与写操作同等对待（docs/AGENT.md §5）
            "prepare_download", AgentToolNature.CONFIRM,
            // 写操作：修改资源标签（落库在 Asset Service），必须经用户确认
            "update_asset_tags", AgentToolNature.CONFIRM
    );

    /** Tool 名 → 调度信息 */
    private final Map<String, ToolSpec> tools;

    /** 交给 Spring AI 的同名工具定义，供真实模型链路使用。 */
    private final List<ToolCallback> springAiCallbacks;

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AgentToolCatalog(List<AgentToolBean> toolBeans, ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.tools = indexToolMethods(toolBeans);
        this.springAiCallbacks = List.of(ToolCallbacks.from(toolBeans.toArray()));
        assertNaturesCoverRegistry();
    }

    /** 注册表里是否有该 Tool。用于拒绝模型给出的未知工具名。 */
    public boolean contains(String name) {
        return name != null && tools.containsKey(name);
    }

    /** 该 Tool 是否必须先经用户确认。 */
    public boolean requiresConfirmation(String name) {
        return NATURES.get(name) == AgentToolNature.CONFIRM;
    }

    /** 全部 Tool 名（用于提示词与日志）。 */
    public Set<String> names() {
        return tools.keySet();
    }

    /** Spring AI 侧的工具定义。 */
    public List<ToolCallback> springAiCallbacks() {
        return springAiCallbacks;
    }

    /**
     * 生成供真实模型使用的工具清单文本（名称 + 说明 + JSON Schema）。
     *
     * <p>说明直接取自 Spring AI 的 {@code ToolDefinition}，与 mock 模式调度的是同一份定义，
     * 不另写一份给模型看的工具说明，否则两份说明迟早会漂移。
     */
    public String promptToolCatalog() {
        StringBuilder sb = new StringBuilder();
        for (ToolCallback callback : springAiCallbacks) {
            var definition = callback.getToolDefinition();
            sb.append("- ").append(definition.name()).append('\n')
                    .append("  description: ").append(definition.description().replace("\n", "\n  ")).append('\n')
                    .append("  inputSchema: ").append(definition.inputSchema()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 按名字调度 Tool。
     *
     * @param name Tool 名，来自模型或计划
     * @param args JSON 参数对象；为 null 时按空对象处理
     * @throws BizException 工具不存在 / 参数不合法 / 执行失败
     */
    public ToolResult invoke(String name, JsonNode args) {
        ToolSpec spec = tools.get(name);
        if (spec == null) {
            // 模型可能编造工具名，必须显式拒绝而不是忽略
            throw new BizException(ErrorCode.AGENT_TOOL_NOT_FOUND, "unknown tool: " + name);
        }
        Object argument = toArgument(spec, args);
        assertValid(spec, argument);
        try {
            Object returned = spec.method().invoke(spec.bean(), argument);
            if (returned instanceof ToolResult result) {
                return result;
            }
            throw new BizException(ErrorCode.AGENT_TOOL_FAILED,
                    "tool '" + name + "' returned unexpected type: "
                            + (returned == null ? "null" : returned.getClass().getName()));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            if (cause instanceof BizException bizException) {
                // Tool 自己抛出的业务异常原样上抛：它有明确的错误码与原因
                throw bizException;
            }
            throw new BizException(ErrorCode.AGENT_TOOL_FAILED, "tool '" + name + "' failed: " + cause.getMessage());
        } catch (IllegalAccessException e) {
            throw new BizException(ErrorCode.AGENT_TOOL_FAILED, "tool '" + name + "' is not invokable");
        }
    }

    /* ------------------------------------------------------------------ 内部方法 */

    private Map<String, ToolSpec> indexToolMethods(List<AgentToolBean> toolBeans) {
        Map<String, ToolSpec> indexed = new LinkedHashMap<>();
        for (AgentToolBean bean : toolBeans) {
            for (Method method : bean.getClass().getMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation == null) {
                    continue;
                }
                String name = annotation.name();
                if (name == null || name.isBlank()) {
                    throw new IllegalStateException("@Tool name must not be blank: "
                            + bean.getClass().getName() + "#" + method.getName());
                }
                if (method.getParameterCount() != 1) {
                    // 统一「单参数对象」形态：便于 JSON 反序列化与 Jakarta 校验
                    throw new IllegalStateException("tool method must take exactly one argument: " + name);
                }
                ToolSpec previous = indexed.put(name, new ToolSpec(name, annotation.description(),
                        method.getParameterTypes()[0], bean, method));
                if (previous != null) {
                    throw new IllegalStateException("duplicate tool name: " + name);
                }
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalStateException("no agent tool registered");
        }
        return Map.copyOf(indexed);
    }

    private void assertNaturesCoverRegistry() {
        List<String> missing = new ArrayList<>();
        for (String name : tools.keySet()) {
            if (!NATURES.containsKey(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "agent tools without declared nature (add them to AgentToolCatalog.NATURES): " + missing);
        }
        Set<String> unknown = new TreeMap<>(NATURES).keySet();
        List<String> stale = unknown.stream().filter(name -> !tools.containsKey(name)).toList();
        if (!stale.isEmpty()) {
            throw new IllegalStateException("AgentToolCatalog.NATURES declares tools that do not exist: " + stale);
        }
    }

    private Object toArgument(ToolSpec spec, JsonNode args) {
        JsonNode payload = args == null || args.isNull() ? objectMapper.createObjectNode() : args;
        try {
            return objectMapper.treeToValue(payload, spec.argumentType());
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "invalid arguments for tool '" + spec.name() + "': " + e.getMessage());
        }
    }

    private void assertValid(ToolSpec spec, Object argument) {
        Set<ConstraintViolation<Object>> violations = validator.validate(argument);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("invalid");
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "invalid arguments for tool '" + spec.name() + "': " + detail);
        }
    }

    private record ToolSpec(String name, String description, Class<?> argumentType, Object bean, Method method) {
    }
}
