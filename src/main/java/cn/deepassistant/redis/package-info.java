/**
 * Redis 生产存储：AgentState、工作区文件、网页会话、HITL 待审批都进同一套 Redis，多副本共享。
 *
 * <ul>
 *   <li>{@link RedisAgentStateStore} —— 对话上下文、权限、计划</li>
 *   <li>{@link RedisBaseStore} —— MEMORY.md、日流水、压缩卸载 jsonl（RemoteFilesystem）</li>
 *   <li>{@link RedisPendingApprovalStore} —— 写文件审批单，避免打到另一副本丢 pending</li>
 * </ul>
 *
 * 官方要求：{@code filesystem(RemoteFilesystemSpec)} 必须配 {@code DistributedStore}，
 * 否则 {@code HarnessAgent.build()} 抛 IllegalStateException。
 */
package cn.deepassistant.redis;
