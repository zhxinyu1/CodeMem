package com.codemem.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {

    List<AgentRun> findTop20ByOrderByCreatedAtDesc();

    List<AgentRun> findTop10ByOrderByCreatedAtDesc();

    List<AgentRun> findByTraceIdOrderByCreatedAtAsc(String traceId);
}