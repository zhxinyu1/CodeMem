package com.codemem.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 笔记详情：让前端能把引用点开，直接看那篇笔记的正文。
 *
 * 安全约束：
 * 1. 只允许读取「当前知识源根目录之内」的文件（normalize + startsWith 校验，防 ../ 穿越）；
 * 2. 只允许读取已经进入索引的文档（必须先能在 knowledge_doc 里查到该 path）；
 * 3. 单篇最多读 512 KB，超出部分截断，避免把超大文件塞进响应。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class NoteDetailController {

    private static final Logger log = LoggerFactory.getLogger(NoteDetailController.class);
    private static final long MAX_BYTES = 512 * 1024;

    private final JdbcTemplate postgresJdbc;
    private final KnowledgeSourceStore sourceStore;

    public NoteDetailController(@Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
                                KnowledgeSourceStore sourceStore) {
        this.postgresJdbc = postgresJdbc;
        this.sourceStore = sourceStore;
    }

    @GetMapping("/note")
    public ResponseEntity<Map<String, Object>> note(@RequestParam("path") String path) {
        String relative = path == null ? "" : path.trim();
        if (relative.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("found", false, "reason", "path 不能为空"));
        }

        KnowledgeSource source = sourceStore.activeOrNull();
        if (source == null) {
            return ResponseEntity.badRequest().body(Map.of("found", false, "reason", "尚未添加知识源"));
        }

        String title;
        try {
            List<String> titles = postgresJdbc.queryForList(
                    "SELECT coalesce(title, '') FROM knowledge_doc WHERE path = ?", String.class, relative);
            if (titles.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("found", false, "reason", "该笔记不在索引中"));
            }
            title = titles.get(0);
        } catch (RuntimeException ex) {
            log.warn("Unable to look up note {}: {}", relative, ex.getMessage());
            return ResponseEntity.status(503).body(Map.of("found", false, "reason", "索引暂不可用"));
        }

        Path root;
        Path target;
        try {
            root = Path.of(source.getRootPath()).toAbsolutePath().normalize();
            target = root.resolve(relative).normalize();
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(Map.of("found", false, "reason", "路径不合法"));
        }

        if (!target.startsWith(root)) {
            return ResponseEntity.badRequest().body(Map.of("found", false, "reason", "路径越界，已拒绝"));
        }
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.status(404).body(Map.of("found", false, "reason", "文件不存在或已被移走", "root", root.toString()));
        }

        try {
            long size = Files.size(target);
            byte[] bytes = Files.readAllBytes(target);
            boolean truncated = bytes.length > MAX_BYTES;
            String content = new String(bytes, 0, (int) Math.min(bytes.length, MAX_BYTES), StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("found", true);
            body.put("path", relative);
            body.put("title", title);
            body.put("content", content);
            body.put("size", size);
            body.put("truncated", truncated);
            return ResponseEntity.ok(body);
        } catch (Exception ex) {
            log.warn("Unable to read note {}: {}", target, ex.getMessage());
            return ResponseEntity.status(500).body(Map.of("found", false, "reason", "读取文件失败：" + ex.getMessage()));
        }
    }
}