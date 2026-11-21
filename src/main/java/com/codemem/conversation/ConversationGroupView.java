package com.codemem.conversation;

/**
 * 分组视图：附组内会话数，前端用于分组标题右侧计数。
 */
public record ConversationGroupView(Long id, String name, long conversationCount) {
}