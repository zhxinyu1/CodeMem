package com.codemem.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeSourceRepository extends JpaRepository<KnowledgeSource, String> {

    List<KnowledgeSource> findAllByOrderByCreatedAtAsc();

    Optional<KnowledgeSource> findFirstByStatus(String status);

    Optional<KnowledgeSource> findByRootPath(String rootPath);

    List<KnowledgeSource> findByStatusNot(String status);
}