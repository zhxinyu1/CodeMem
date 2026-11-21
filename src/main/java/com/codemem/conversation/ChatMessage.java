package com.codemem.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "turn_id", length = 64)
    private String turnId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ChatMessage() {
    }

    public ChatMessage(Long conversationId,
                       String turnId,
                       String role,
                       String content,
                       LocalDateTime createdAt) {
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}