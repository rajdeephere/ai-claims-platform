package com.claimsai.activity.api;

import com.claimsai.activity.api.ActivityDtos.ActivityResponse;
import com.claimsai.activity.api.ActivityDtos.CompleteRequest;
import com.claimsai.activity.api.ActivityDtos.DashboardResponse;
import com.claimsai.activity.app.ActivityService;
import com.claimsai.activity.app.SupervisorDashboardService;
import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.common.web.PageResponse;
import com.claimsai.identity.app.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Tasks for staff, and the supervisor's overview. */
@RestController
@PreAuthorize("hasAnyRole('ADJUSTER', 'SUPERVISOR', 'SIU')")
@Tag(name = "Activities", description = "Tasks with an SLA, and the supervisor dashboard")
public class ActivityController {

    private final ActivityService activities;
    private final SupervisorDashboardService dashboard;
    private final CurrentUserProvider currentUser;

    public ActivityController(ActivityService activities, SupervisorDashboardService dashboard,
                              CurrentUserProvider currentUser) {
        this.activities = activities;
        this.dashboard = dashboard;
        this.currentUser = currentUser;
    }

    private static PageRequest dueFirst(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.asc("dueAt"), Sort.Order.asc("id")));
    }

    @GetMapping("/api/v1/activities")
    @Operation(operationId = "listMyActivities", summary = "My open activities, soonest due first",
            description = "Assigned to me, or in my role's queue (supervisors, SIU). overdueOnly: past their due time.")
    public PageResponse<ActivityResponse> mine(@RequestParam(defaultValue = "false") boolean overdueOnly,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(activities.mine(currentUser.get(), overdueOnly, dueFirst(page, size)),
                ActivityResponse::of);
    }

    @GetMapping("/api/v1/activities/breached")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @Operation(operationId = "listBreachedActivities", summary = "Every open activity past its SLA, oldest due first")
    public PageResponse<ActivityResponse> breached(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(activities.breached(dueFirst(page, size)), ActivityResponse::of);
    }

    @PostMapping("/api/v1/activities/{id}/complete")
    @Operation(operationId = "completeActivity", summary = "Complete an activity (its owner, or a supervisor)")
    @DocumentedErrors({404, 409, 422})
    public ActivityResponse complete(@PathVariable Long id, @Valid @RequestBody(required = false) CompleteRequest request) {
        return ActivityResponse.of(activities.complete(id, request == null ? null : request.note(), currentUser.get()));
    }

    @GetMapping("/api/v1/claims/{claimId}/activities")
    @Operation(operationId = "listClaimActivities", summary = "All activities on a claim, open and closed")
    @DocumentedErrors({404})
    public List<ActivityResponse> forClaim(@PathVariable Long claimId) {
        return activities.forClaim(claimId, currentUser.get()).stream().map(ActivityResponse::of).toList();
    }

    @GetMapping("/api/v1/dashboard/supervisor")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @Operation(operationId = "getSupervisorDashboard", summary = "Queue sizes, pending approvals, SIU cases, SLA breaches")
    public DashboardResponse supervisorDashboard() {
        return DashboardResponse.of(dashboard.dashboard());
    }
}
