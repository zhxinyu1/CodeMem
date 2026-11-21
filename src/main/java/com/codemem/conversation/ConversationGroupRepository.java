package com.codemem.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationGroupRepository extends JpaRepository<ConversationGroup, Long> {

    List<ConversationGroup> findByLearnerIdOrderBySortOrderAscIdAsc(String learnerId);

    Optional<ConversationGroup> findByIdAndLearnerId(Long id, String learnerId);

    boolean existsByLearnerIdAndName(String learnerId, String name);
}