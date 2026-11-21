package com.codemem.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A note library registered as a knowledge source. Paths are stored as absolute
 * paths on the host running the app; the scanner only reads, never writes.
 */
@Entity
@Table(name = "knowledge_source")
public class KnowledgeSource {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 24)
    private String type;

    @Column(name = "root_path", nullable = false, length = 1000)
    private String rootPath;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "doc_count", nullable = false)
    private int docCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_indexed_at")
    private LocalDateTime lastIndexedAt;

    protected KnowledgeSource() {
    }

    public KnowledgeSource(String id,
                           String name,
                           String type,
                           String rootPath,
                           LocalDateTime now) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.rootPath = rootPath;
        this.status = "INACTIVE";
        this.docCount = 0;
        this.chunkCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    public String getStatus() {
        return status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public void activate() {
        this.status = "ACTIVE";
    }

    public void deactivate() {
        this.status = "INACTIVE";
    }

    public int getDocCount() {
        return docCount;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void markIndexed(int docCount, int chunkCount, LocalDateTime indexedAt) {
        this.docCount = docCount;
        this.chunkCount = chunkCount;
        this.lastIndexedAt = indexedAt;
        this.updatedAt = indexedAt;
    }

    

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getLastIndexedAt() {
        return lastIndexedAt;
    }

    public KnowledgeSourceType typeEnum() {
        return KnowledgeSourceType.parse(type);
    }

    public void touch(LocalDateTime now) {
        this.updatedAt = now;
    }
}