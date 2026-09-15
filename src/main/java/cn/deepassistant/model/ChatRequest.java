package cn.deepassistant.model;

import lombok.Data;

/**
 * 聊天请求。{@code userId} 决定这轮对话归谁——多用户隔离的入口。
 * 不传时后端回退到 {@code "local"}，等价于原来的单用户模式。
 */
@Data
public class ChatRequest {
    private String userId;
    private String sessionId;
    private String message;
    private Boolean showTools;
}
