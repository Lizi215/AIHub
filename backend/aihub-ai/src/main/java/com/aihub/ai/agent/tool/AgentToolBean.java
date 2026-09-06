package com.aihub.ai.agent.tool;

/**
 * Tool 实现类的标记接口，让 {@link AgentToolCatalog} 能精确拿到 Tool Bean 列表。
 *
 * <p>若改成注入 {@code List<Object>}，任何 Bean 都会被反射扫一遍，既慢又容易
 * 把无关方法误当成 Tool。
 */
public interface AgentToolBean {
}
