package com.aihub.ai.service;

import com.aihub.ai.dto.CreateAgentTaskRequest;
import com.aihub.ai.vo.AgentTaskVo;

/**
 * Agent 任务服务（docs/AGENT.md §4 的接口对应的业务能力）。
 *
 * <p>每个方法都显式接收 {@code currentUserId} 而不是内部读 {@code UserContext}：
 * 确认识别与查询会在后台线程里发生，ThreadLocal 在那里是空的，显式传参可避免
 * 身份悄悄丢失导致的越权或误判。
 */
public interface AgentTaskService {

    /** 创建任务并立即返回 taskId（禁止用同步 HTTP 请求承载执行过程）。 */
    Long create(Long currentUserId, CreateAgentTaskRequest request);

    /** 查询任务状态与结果。 */
    AgentTaskVo detail(Long taskId, Long currentUserId);

    /** 提交确认：任务从 WAITING_CONFIRM 继续执行。 */
    AgentTaskVo confirm(Long taskId, Long currentUserId);

    /** 取消任务。 */
    AgentTaskVo cancel(Long taskId, Long currentUserId);
}
