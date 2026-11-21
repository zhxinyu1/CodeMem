package com.codemem.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge sources: list, register, switch, remove.
 *
 * Switching rebuilds the index synchronously by default, because serving answers
 * from a half-switched index (old source purged, new source not yet indexed) would
 * silently degrade retrieval quality. Pass {@code reindex=false} to switch now and
 * rebuild later via {@code POST /api/v1/knowledge/reindex}.
 */
@RestController
@RequestMapping("/api/v1/knowledge/sources")
public class KnowledgeSourceController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSourceController.class);

    private final KnowledgeSourceStore sourceStore;
    private final KnowledgeIndexService indexService;

    public KnowledgeSourceController(KnowledgeSourceStore sourceStore,
                                     KnowledgeIndexService indexService) {
        this.sourceStore = sourceStore;
        this.indexService = indexService;
    }

    @GetMapping
    public KnowledgeSourcesView list() {
        // 不在这里“顺手”初始化默认源：没导入过笔记时就该显示未配置
        return KnowledgeSourcesView.of(sourceStore.list());
    }

    @PostMapping
    public KnowledgeSourceCreatedView create(@RequestBody CreateKnowledgeSourceRequest request) {
        KnowledgeSource source = sourceStore.create(request);
        IndexResult result = null;
        if (source.isActive() && request.reindexOrDefault()) {
            result = indexService.reindexActive();
        }
        log.info("Knowledge source '{}' registered at {} (active={})",
                source.getName(), source.getRootPath(), source.isActive());
        return new KnowledgeSourceCreatedView(KnowledgeSourceView.from(source), result);
    }

    @PostMapping("/{id}/activate")
    public KnowledgeSourceCreatedView activate(@PathVariable String id,
                                              @RequestParam(defaultValue = "true") boolean reindex) {
        KnowledgeSource source = sourceStore.activate(id);
        IndexResult result = reindex ? indexService.reindexActive() : null;
        return new KnowledgeSourceCreatedView(KnowledgeSourceView.from(source), result);
    }

    /** 单个知识源卡片里的「重建索引」：只重建这个源，其他源的索引不动。 */
    @PostMapping("/{id}/reindex")
    public IndexResult reindex(@PathVariable String id) {
        return indexService.reindexSource(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        sourceStore.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record KnowledgeSourceCreatedView(KnowledgeSourceView source, IndexResult index) {
    }
}