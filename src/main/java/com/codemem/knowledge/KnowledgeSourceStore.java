package com.codemem.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry of note libraries. Exactly one source is ACTIVE; the index only ever
 * holds documents of the active source, so switching is a clean replacement
 * rather than a merge of several libraries.
 */
@Service
public class KnowledgeSourceStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSourceStore.class);

    private final KnowledgeSourceRepository sources;

    public KnowledgeSourceStore(KnowledgeSourceRepository sources) {
        this.sources = sources;
    }

    /**
     * 当前启用的知识源。全新环境里一个源都没有，这里**不做任何自动创建**：
     * 用户没导入笔记之前，知识库就该是空的，不能凭空按默认路径登记一个库。
     */
    @Transactional(readOnly = true)
    public synchronized Optional<KnowledgeSource> findActive() {
        return sources.findFirstByStatus("ACTIVE");
    }

    /**
     * 取当前启用的知识源，没有就报错。供“必须有个库才能干活”的路径使用。
     */
    @Transactional(readOnly = true)
    public KnowledgeSource requireActive() {
        return findActive().orElseThrow(() ->
                new IllegalArgumentException("尚未添加知识源，请先在设置页添加一个笔记库"));
    }

    /**
     * 旧入口保留：有源就返回，没有则返回 null（调用方自行决定降级）。
     */
    @Transactional(readOnly = true)
    public KnowledgeSource activeOrNull() {
        return findActive().orElse(null);
    }

    /** 按 id 取知识源，不存在时报错。 */
    @Transactional(readOnly = true)
    public KnowledgeSource require(String id) {
        return sources.findById(id).orElseThrow(() ->
                new IllegalArgumentException("知识源不存在：" + id));
    }

    @Transactional
    public synchronized KnowledgeSource create(CreateKnowledgeSourceRequest request) {
        String rawPath = request.rootPath() == null ? "" : request.rootPath().trim();
        if (rawPath.isBlank()) {
            throw new IllegalArgumentException("rootPath 不能为空");
        }
        Path root;
        try {
            root = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("rootPath 不是合法路径: " + rawPath);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("目录不存在或不可读: " + root);
        }

        String type = KnowledgeSourceType.parse(request.type()).wireName();
        KnowledgeSource source = sources.findByRootPath(root.toString()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (source == null) {
            source = sources.save(new KnowledgeSource(
                    UUID.randomUUID().toString(),
                    nameOf(request.name(), root),
                    type,
                    root.toString(),
                    now
            ));
        } else if (!source.getType().equals(type)) {
            source.setType(type);
            source.touch(now);
            source = sources.save(source);
        }

        if (request.activateOrDefault()) {
            deactivateAll();
            source.activate();
            source.touch(now);
            source = sources.save(source);
        }
        return source;
    }

    @Transactional
    public synchronized KnowledgeSource activate(String id) {
        KnowledgeSource source = sources.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("knowledge source not found: " + id));
        if (!Files.isDirectory(Path.of(source.getRootPath()))) {
            throw new IllegalArgumentException("目录不存在或不可读，无法切换: " + source.getRootPath());
        }
        deactivateAll();
        source.activate();
        source.touch(LocalDateTime.now());
        KnowledgeSource saved = sources.save(source);
        log.info("Knowledge source switched to '{}' ({})", saved.getName(), saved.getRootPath());
        return saved;
    }

    @Transactional
    public synchronized void delete(String id) {
        KnowledgeSource source = sources.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("knowledge source not found: " + id));
        if (source.isActive()) {
            throw new IllegalArgumentException("当前使用中的知识源不能删除，请先切换到其他知识源");
        }
        sources.delete(source);
    }

    @Transactional
    public void markIndexed(String id, int docCount, int chunkCount) {
        sources.findById(id).ifPresent(source -> {
            source.markIndexed(docCount, chunkCount, LocalDateTime.now());
            sources.save(source);
        });
    }

    /** 清空知识库：移除全部已登记的知识源（只是取消登记，磁盘上的笔记不动）。 */
    @Transactional
    public synchronized void deleteAll() {
        sources.deleteAll();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeSource> list() {
        return sources.findAllByOrderByCreatedAtAsc();
    }

    private void deactivateAll() {
        List<KnowledgeSource> all = sources.findAllByOrderByCreatedAtAsc();
        for (KnowledgeSource candidate : all) {
            if (candidate.isActive()) {
                candidate.deactivate();
                sources.save(candidate);
            }
        }
    }

    private String nameOf(String name, Path root) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return defaultName(root);
    }

    private String defaultName(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }
}