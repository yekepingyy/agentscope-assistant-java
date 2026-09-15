package cn.deepassistant.redis;

import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;

import java.util.List;
import java.util.Map;

/**
 * Redis 里的待审批工具快照。不要直接把 {@link ToolUseBlock} 丢给 Jackson：
 * 它没有默认构造器，反序列化会失败——key 还在，{@code get()} 却是 null，续跑报「没有待审批」。
 *
 * <p><b>何时调用：</b>{@link RedisPendingApprovalStore#put} 写入、{@code get} 读出后再
 * {@link #toBlock()} 还原成框架要的 {@link ToolUseBlock}。
 */
public record PendingToolCall(
        String id,
        String name,
        Map<String, Object> input,
        String content,
        Map<String, Object> metadata,
        String state
) {

    static PendingToolCall from(ToolUseBlock block) {
        if (block == null) {
            return null;
        }
        return new PendingToolCall(
                block.getId(),
                block.getName(),
                block.getInput(),
                block.getContent(),
                block.getMetadata(),
                block.getState() == null ? null : block.getState().name());
    }

    /**
     * 还原成 Harness 续跑 {@code ConfirmResult} 需要的 {@link ToolUseBlock}。
     */
    ToolUseBlock toBlock() {
        ToolUseBlock.Builder builder = ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(input == null ? Map.of() : input)
                .content(content)
                .metadata(metadata);
        ToolCallState parsed = parseState(state);
        if (parsed != null) {
            builder.state(parsed);
        }
        return builder.build();
    }

    private static ToolCallState parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return ToolCallState.ASKING;
        }
        try {
            return ToolCallState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return ToolCallState.ASKING;
        }
    }

    /** write_file / edit_file 的目标路径；没有就空串。 */
    public static String pathOf(ToolUseBlock block) {
        Object path = inputValue(block, "path");
        if (path == null) {
            path = inputValue(block, "file_path");
        }
        return path == null ? "" : String.valueOf(path);
    }

    /** 即将写入的正文；优先 input.content，其次 block.content。 */
    public static String contentOf(ToolUseBlock block) {
        Object content = inputValue(block, "content");
        if (content != null) {
            return String.valueOf(content);
        }
        if (block == null || block.getContent() == null) {
            return "";
        }
        return block.getContent();
    }

    /** 审批卡片 / 会话摘要用：{@code write_file → reports/weekly-xxx.md}。 */
    public static String describe(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "未知工具";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolUseBlock block = toolCalls.get(i);
            if (i > 0) {
                text.append("；");
            }
            String name = block == null || block.getName() == null || block.getName().isBlank()
                    ? "未知工具" : block.getName();
            text.append(name);
            String path = pathOf(block);
            if (!path.isBlank()) {
                text.append(" → ").append(path);
            }
        }
        return text.toString();
    }

    private static Object inputValue(ToolUseBlock block, String key) {
        if (block == null || block.getInput() == null || key == null) {
            return null;
        }
        return block.getInput().get(key);
    }
}
