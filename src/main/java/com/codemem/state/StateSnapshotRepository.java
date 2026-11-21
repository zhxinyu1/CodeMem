package com.codemem.state;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StateSnapshotRepository extends JpaRepository<StateSnapshot, Long> {

    Optional<StateSnapshot> findTopByLearnerIdAndProjectIdOrderByVersionDesc(String learnerId, String projectId);

    Optional<StateSnapshot> findByLearnerIdAndProjectIdAndVersion(String learnerId, String projectId, Integer version);

    List<StateSnapshot> findByLearnerIdAndProjectIdOrderByVersionAsc(String learnerId, String projectId);
}