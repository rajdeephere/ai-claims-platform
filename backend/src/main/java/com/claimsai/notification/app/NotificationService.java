package com.claimsai.notification.app;

import com.claimsai.common.error.NotFoundException;
import com.claimsai.notification.domain.Notification;
import com.claimsai.notification.infra.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Notification> forRecipient(Long userId, Pageable page) {
        return notifications.findByRecipientUserId(userId, page);
    }

    @Transactional(readOnly = true)
    public long unread(Long userId) {
        return notifications.countByRecipientUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public Notification markRead(Long id, Long userId) {
        Notification notification = notifications.findByIdAndRecipientUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("NOTIFICATION_NOT_FOUND", "Notification " + id + " not found"));
        notification.markRead(clock.instant());
        return notification;
    }
}
