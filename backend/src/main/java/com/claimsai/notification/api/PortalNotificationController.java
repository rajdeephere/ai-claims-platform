package com.claimsai.notification.api;

import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.common.web.PageResponse;
import com.claimsai.common.web.Paging;
import com.claimsai.identity.app.CurrentUserProvider;
import com.claimsai.notification.app.NotificationService;
import com.claimsai.notification.domain.Notification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/portal/notifications")
@PreAuthorize("hasRole('CLAIMANT')")
@Tag(name = "Portal: notifications", description = "Messages about my claims")
public class PortalNotificationController {

    private final NotificationService notifications;
    private final CurrentUserProvider currentUser;

    public PortalNotificationController(NotificationService notifications, CurrentUserProvider currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    public record NotificationView(Long id, Long claimId, String subject, String body, Instant createdAt,
                                   Instant readAt) {

        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getClaimId(), n.getSubject(), n.getBody(), n.getCreatedAt(),
                    n.getReadAt());
        }
    }

    public record UnreadCount(long unread) {
    }

    @GetMapping
    @Operation(operationId = "listMyNotifications", summary = "My notifications, newest first")
    public PageResponse<NotificationView> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(notifications.forRecipient(currentUser.get().id(), Paging.newestFirst(page, size)),
                NotificationView::of);
    }

    @GetMapping("/unread-count")
    @Operation(operationId = "getMyUnreadNotificationCount", summary = "Number of unread notifications (for the badge)")
    public UnreadCount unread() {
        return new UnreadCount(notifications.unread(currentUser.get().id()));
    }

    @PostMapping("/{id}/read")
    @Operation(operationId = "markNotificationRead", summary = "Mark as read (idempotent)")
    @DocumentedErrors({404})
    public NotificationView markRead(@PathVariable Long id) {
        return NotificationView.of(notifications.markRead(id, currentUser.get().id()));
    }
}
