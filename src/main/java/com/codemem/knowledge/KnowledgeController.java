package com.codemem.knowledge;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeIndexService indexService;
    private final KnowledgeSearchService searchService;

    public KnowledgeController(KnowledgeIndexService indexService,
                               KnowledgeSearchService searchService) {
        this.indexService = indexService;
        this.searchService = searchService;
    }

    /** 顶部「重建索引」：重建下面所有知识源的索引（与单个知识源卡片里的重建不同）。 */
    @PostMapping("/reindex")
    public ReindexSummary reindex() {
        return indexService.reindexAllSources();
    }

    /** 顶部「清空索引」：清空全部知识源的索引，知识库回到 0 篇 / 0 块。 */
    @PostMapping("/clear")
    public ClearIndexResult clear() {
        return indexService.clearAll();
    }

    @GetMapping("/status")
    public IndexStatus status() {
        return indexService.status();
    }

    @GetMapping("/search")
    public List<RetrievedChunk> search(@RequestParam("q") String query,
                                       @RequestParam(value = "topK", defaultValue = "4") int topK) {
        return searchService.search(query, topK);
    }
}