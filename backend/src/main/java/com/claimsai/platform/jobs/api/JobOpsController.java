package com.claimsai.platform.jobs.api;

import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.common.web.PageResponse;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.domain.Job;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The small operations view that replaces Camunda Operate: see failed background work and retry it once
 * the cause is fixed.
 */
@RestController
@RequestMapping("/api/v1/ops/jobs")
@PreAuthorize("hasRole('SUPERVISOR')")
@Tag(name = "Operations", description = "Background jobs")
public class JobOpsController {

    private final JobService jobs;

    public JobOpsController(JobService jobs) {
        this.jobs = jobs;
    }

    public record JobView(Long id, String type, Long claimId, Job.Status status, int attempts, int maxAttempts,
                          Instant dueAt, String lastError, String correlationId, Instant createdAt,
                          Instant updatedAt) {

        static JobView of(Job j) {
            return new JobView(j.getId(), j.getType(), j.getClaimId(), j.getStatus(), j.getAttempts(),
                    j.getMaxAttempts(), j.getDueAt(), j.getLastError(), j.getCorrelationId(), j.getCreatedAt(),
                    j.getUpdatedAt());
        }
    }

    @GetMapping
    @Operation(operationId = "listJobs", summary = "Jobs by status, most recently changed first", description = "Default: FAILED")
    public PageResponse<JobView> list(@RequestParam(defaultValue = "FAILED") Job.Status status,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(jobs.byStatus(status, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")))), JobView::of);
    }

    @PostMapping("/{id}/retry")
    @Operation(operationId = "retryJob", summary = "Retry a FAILED job now, with a fresh set of attempts")
    @DocumentedErrors({404, 409})
    public JobView retry(@PathVariable Long id) {
        return JobView.of(jobs.retry(id));
    }
}
