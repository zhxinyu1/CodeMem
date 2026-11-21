package com.codemem.state;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StateEventRepository extends JpaRepository<StateEvent, Long> {

    List<StateEvent> findByLearnerIdAndProjectIdOrderByCreatedAtAsc(String learnerId, String projectId);
}