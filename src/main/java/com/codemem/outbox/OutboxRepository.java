package com.codemem.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

    @Query("""
            select e from OutboxEvent e
            where e.status = 'PENDING'
              and (e.nextRetryAt is null or e.nextRetryAt <= :now)
            order by e.createdAt asc
            """)
    List<OutboxEvent> findPending(@Param("now") LocalDateTime now, Pageable pageable);

    List<OutboxEvent> findTop20ByOrderByCreatedAtDesc();

    /**
     * 卡死判定：优先看认领时间；历史数据没有 claimed_at 时退回 createdAt 兼容。
     */
    @Query("""
            select e from OutboxEvent e
            where e.status = 'PROCESSING'
              and ((e.claimedAt is not null and e.claimedAt < :cutoff)
                   or (e.claimedAt is null and e.createdAt < :cutoff))
            """)
    List<OutboxEvent> findStaleProcessing(@Param("cutoff") LocalDateTime cutoff);

    long countByStatus(String status);
}