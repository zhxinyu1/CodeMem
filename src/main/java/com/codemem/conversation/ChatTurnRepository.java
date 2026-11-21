package com.codemem.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatTurnRepository extends JpaRepository<ChatTurn, String> {
}