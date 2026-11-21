package com.codemem.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByLearnerIdOrderByUpdatedAtDesc(String learnerId, Pageable pageable);
}