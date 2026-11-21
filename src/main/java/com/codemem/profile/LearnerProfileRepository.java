package com.codemem.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LearnerProfileRepository extends JpaRepository<LearnerProfile, Long> {

    Optional<LearnerProfile> findByLearnerId(String learnerId);
}