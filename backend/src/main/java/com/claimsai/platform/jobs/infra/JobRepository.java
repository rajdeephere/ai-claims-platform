package com.claimsai.platform.jobs.infra;

import com.claimsai.platform.jobs.domain.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    Page<Job> findByStatus(Job.Status status, Pageable pageable);

    Optional<Job> findByDedupKey(String dedupKey);

    List<Job> findByClaimIdOrderByIdAsc(Long claimId);
}
