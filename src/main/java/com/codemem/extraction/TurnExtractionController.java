package com.codemem.extraction;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.PageRequest;

import java.util.List;

@RestController
@RequestMapping("/api/v1/extractions")
public class TurnExtractionController {

    private final TurnExtractionRepository repository;

    public TurnExtractionController(TurnExtractionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/recent")
    public List<TurnExtractionView> recent(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.max(1, Math.min(200, size));
        int safePage = Math.max(0, page);
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(safePage, safeSize)).stream()
                .map(TurnExtractionView::from)
                .toList();
    }
}