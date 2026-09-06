package com.aihub.ai.agent.orchestrator;

import com.aihub.ai.agent.plan.AgentPlanningState;
import com.aihub.ai.agent.plan.PlannerDecision;
import com.aihub.ai.config.AiChatClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 规划器路由：决定这次任务用真实模型还是 mock。
 *
 * <p>选择依据只有一条：有没有可用的 API Key。这样同一份代码在任何机器上都能跑，
 * 任务里记录了实际使用的模式（{@code agent_task.model}），事后能看出某次结果
 * 到底是模型还是 mock 产出的。
 */
@Component
@Primary
public class AgentPlannerRouter implements AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AgentPlannerRouter.class);

    private final MockAgentPlanner mockPlanner;
    private final SpringAiAgentPlanner springAiPlanner;
    private final AiChatClientFactory chatClientFactory;

    public AgentPlannerRouter(MockAgentPlanner mockPlanner,
                              SpringAiAgentPlanner springAiPlanner,
                              AiChatClientFactory chatClientFactory) {
        this.mockPlanner = mockPlanner;
        this.springAiPlanner = springAiPlanner;
        this.chatClientFactory = chatClientFactory;
    }

    @Override
    public String mode() {
        return delegate().mode();
    }

    @Override
    public PlannerDecision next(AgentPlanningState state) {
        return delegate().next(state);
    }

    private AgentPlanner delegate() {
        if (chatClientFactory.available()) {
            log.debug("agent planner uses real model");
            return springAiPlanner;
        }
        return mockPlanner;
    }
}
