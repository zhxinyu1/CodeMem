package com.codemem.extraction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TurnExtractionRepository extends JpaRepository<TurnExtraction, Long> {

    Optional<TurnExtraction> findByTurnId(String turnId);

    List<TurnExtraction> findAllByOrderByCreatedAtDesc(Pageable pageable);
}