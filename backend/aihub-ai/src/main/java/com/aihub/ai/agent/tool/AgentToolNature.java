package com.aihub.ai.agent.tool;

/**
 * Tool 的性质，决定它是否必须先经过用户确认（docs/AGENT.md §5）。
 *
 * <p>这是代码层的门禁，不是提示词里的约定：{@code AgentOrchestrator} 在确认之前
 * 会硬拒绝执行 {@link #CONFIRM} 类 Tool。提示词可能被注入内容影响，代码不会。
 */
public enum AgentToolNature {

    /** 只读：检索、查询、分析。任何阶段都可执行。 */
    READ,

    /**
     * 需确认：下载、批量下载、创建 Workspace、批量收藏等实际产生后果的操作。
     *
     * <p>「产生后果」不限于写数据库：一次真实下载会消耗带宽、写入下载记录、影响热门榜，
     * 因此与写操作同等对待。
     */
    CONFIRM
}
