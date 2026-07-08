package com.aihub.common.result;

/**
 * 错误码。号码段归属见 docs/API.md §3，禁止各服务自行发明号码段。
 *
 * <pre>
 * 0      成功
 * 1xxxx  用户域
 * 2xxxx  资源域
 * 3xxxx  社区域
 * 4xxxx  搜索域
 * 5xxxx  文件域
 * 9xxxx  AI 域 / 系统级
 * </pre>
 *
 * 新增错误码必须在本枚举中定义，不允许在业务代码里硬编码数字。
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),

    /* ---------------------------------------------------------- 用户域 */
    UNAUTHORIZED(10001, "authentication required"),
    FORBIDDEN(10002, "permission denied"),
    USERNAME_ALREADY_EXISTS(10003, "username already exists"),
    USER_NOT_FOUND(10004, "user not found"),
    PASSWORD_INCORRECT(10005, "incorrect username or password"),
    /** 签名不合法或格式非法 */
    TOKEN_INVALID(10006, "invalid token"),
    /** 签名合法但已过期，与 TOKEN_INVALID 区分，便于前端决定是否续期 */
    TOKEN_EXPIRED(10007, "token expired"),
    LOGIN_ATTEMPT_LIMITED(10008, "too many failed login attempts, please retry later"),

    /* ---------------------------------------------------------- 资源域 */
    ASSET_NOT_FOUND(20001, "asset not found"),
    ASSET_ACCESS_DENIED(20002, "asset access denied"),
    ASSET_STATUS_INVALID(20003, "asset status does not allow this operation"),
    ASSET_VERSION_ALREADY_EXISTS(20004, "asset version already exists"),
    ASSET_DEPENDENCY_ALREADY_EXISTS(20005, "asset dependency already exists"),
    /** 依赖成环（含自依赖）。docs/ARCHITECTURE.md §8.4 要求必须拦截 */
    ASSET_DEPENDENCY_CYCLE(20006, "asset dependency would create a cycle"),
    ASSET_VERSION_NOT_FOUND(20007, "asset version not found"),
    ASSET_DEPENDENCY_NOT_FOUND(20008, "asset dependency not found"),

    /* ---------------------------------------------------------- 社区域 */
    POST_NOT_FOUND(30001, "post not found"),
    ISSUE_NOT_FOUND(30002, "issue not found"),
    /** Issue 状态机不允许的流转（docs/ARCHITECTURE.md §7） */
    ISSUE_STATUS_TRANSITION_INVALID(30003, "issue status transition is not allowed"),

    /* ---------------------------------------------------------- 搜索域 */
    /** ES 不可用或请求失败：搜索是派生能力，失败不应影响业务写入，但必须如实告知调用方 */
    SEARCH_ENGINE_UNAVAILABLE(40001, "search engine unavailable"),
    /** 索引的建 / 更新 / 删除失败 */
    SEARCH_INDEX_OPERATION_FAILED(40002, "search index operation failed"),
    /** 查询参数不合法（例如非法的排序字段） */
    SEARCH_QUERY_INVALID(40003, "invalid search query"),
    SEARCH_DOCUMENT_NOT_FOUND(40004, "search document not found"),

    /* ---------------------------------------------------------- 文件域 */
    FILE_UPLOAD_FAILED(50001, "file upload failed"),
    FILE_TYPE_NOT_ALLOWED(50002, "file type not allowed"),
    FILE_TOO_LARGE(50003, "file too large"),
    FILE_NOT_FOUND(50004, "file not found"),
    FILE_EMPTY(50005, "file is empty"),

    /* ---------------------------------------------------------- AI 域 */
    AGENT_TASK_NOT_FOUND(91001, "agent task not found"),
    /** Agent 任务状态机不允许的流转（docs/AGENT.md §4） */
    AGENT_TASK_STATUS_TRANSITION_INVALID(91002, "agent task status transition is not allowed"),
    /** 任务不在等待确认状态，却调用了确认接口 */
    AGENT_TASK_NOT_AWAITING_CONFIRM(91003, "agent task is not awaiting confirmation"),
    /** 非发起者访问他人任务 */
    AGENT_TASK_ACCESS_DENIED(91004, "agent task access denied"),
    AGENT_STEP_LIMIT_EXCEEDED(91005, "agent step limit exceeded"),
    AGENT_TOOL_TIMEOUT(91006, "agent tool call timed out"),
    /** 相同 Tool + 相同参数重复调用（循环检测，docs/AGENT.md §7） */
    AGENT_LOOP_DETECTED(91007, "agent repeated the same tool call"),
    AGENT_TOKEN_BUDGET_EXCEEDED(91008, "agent token budget exceeded"),
    /** 模型给出的 Tool 名不在注册表中，参数与名称都不可信任 */
    AGENT_TOOL_NOT_FOUND(91009, "agent tool not found"),
    /** 未确认即尝试执行需确认的 Tool（写入 / 下载等） */
    AGENT_TOOL_CONFIRMATION_REQUIRED(91010, "agent tool requires user confirmation"),
    /** Tool 执行失败；下游错误已在此转换为本服务的业务异常，不透出下游错误码 */
    AGENT_TOOL_FAILED(91011, "agent tool execution failed"),
    AGENT_SCENE_UNSUPPORTED(91012, "agent scene not supported"),
    /** 单任务总时长超时（单次 Tool 调用超时见 AGENT_TOOL_TIMEOUT） */
    AGENT_TASK_TIMEOUT(91013, "agent task timed out"),

    /* ---------------------------------------------------------- 系统级 */
    SYSTEM_ERROR(90000, "system error"),
    PARAM_INVALID(90001, "invalid parameter"),
    /** 请求被 Sentinel 限流（Phase 7）：HTTP 状态 429，调用方可稍后重试 */
    RATE_LIMITED(90002, "rate limited, please retry later"),
    NOT_FOUND(90004, "resource not found");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
