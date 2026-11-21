package com.codemem.knowledge;

import java.util.List;

/** Current knowledge source plus every registered source, for the settings page. */
public record KnowledgeSourcesView(
        KnowledgeSourceView active,
        List<KnowledgeSourceView> sources
) {
    public static KnowledgeSourcesView of(List<KnowledgeSource> sources) {
        KnowledgeSourceView active = null;
        List<KnowledgeSourceView> views = sources.stream()
                .map(KnowledgeSourceView::from)
                .toList();
        for (KnowledgeSourceView view : views) {
            if (view.active()) {
                active = view;
                break;
            }
        }
        return new KnowledgeSourcesView(active, views);
    }
}