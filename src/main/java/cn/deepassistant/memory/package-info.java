/**
 * 记忆分三层，对应 AgentScope Java 2.0 官方文档，而不是原 LangGraph 项目自己的 MemoryStore。
 *
 * <pre>
 *  ① 短期：当前会话 messages（MySQL AgentState）+ 本项目 SessionStore（网页侧栏，Redis as:web:）
 *  ② 中期日流水：memory/YYYY-MM-DD.md（RemoteFilesystem → Redis）
 *       由官方 MemoryFlushMiddleware 在每次 call 结束后后台抽取（只追加、不去重）
 *  ③ 长期策划：MEMORY.md（同上）
 *       由官方 MemoryConsolidator 周期性合并日流水、去重后整体重写
 *       每一轮推理都会注入 system prompt
 *
 *  压缩 Compaction：上下文太长时摘要前缀、保留尾部；压缩前会再 flush 一次
 *  原文卸载：压缩掉的消息写入 sessions/*.log.jsonl（Redis 工作区），官方 session_search 能搜到
 * </pre>
 *
 * 本包里的 Java 类只做两件事：<b>读</b>官方已经写到 Redis 工作区的记忆文件，以及检索网页会话 JSON。
 * 不要再另写一套抽取器去覆盖 MEMORY.md，否则会和 MemoryConsolidator 抢文件。
 */
package cn.deepassistant.memory;
