package com.codemem.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {

    List<AgentStep> findByRunIdOrderByStepNoAsc(String runId);
}