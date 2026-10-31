package io.zer0.muse.web

/**
 * Host 协议里**只读**命令的数据源集合。
 *
 * 这些数据源彼此独立（记忆 / 知识库 / 定时任务 / 系统状态），但都只是「给网页端看」的只读投影，
 * 且都允许未装配（未装配时命令照常回执，只是返回空列表）。把它们收成一个参数，
 * 避免网关的构造参数随每读一类数据就多一个。
 */
internal data class HostReadSources(
    /** 记忆只读命令的数据源；未装配时下发空列表。 */
    val memory: HostMemorySource = HostMemorySource.Empty,
    /** 知识只读命令的数据源；未装配时下发空列表 / knowledge_base_not_found。 */
    val knowledge: HostKnowledgeSource = HostKnowledgeSource.Empty,
    /** 自动化只读命令的数据源；未装配时下发空列表。 */
    val tasks: HostTaskSource = HostTaskSource.Empty,
    /** 系统状态只读命令的数据源；未装配时下发全默认状态。 */
    val system: HostSystemStatusSource = HostSystemStatusSource.Empty,
) {
    companion object {
        /** 全部未装配：只用于测试与最小可用装配。 */
        val Empty = HostReadSources()
    }
}
