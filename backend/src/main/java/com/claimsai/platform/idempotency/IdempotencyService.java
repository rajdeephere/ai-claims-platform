package com.claimsai.platform.idempotency;

import com.claimsai.common.error.BadRequestException;
import com.claimsai.common.error.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Idempotency keys for "create" requests (ADR-0011). The client generates a key (a UUID) per logical
 * request and reuses it on retries; the same key with the same body returns the original resource, the
 * same key with a different body is refused.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{8,100}");

    private final IdempotencyRepository records;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    public static String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUIRED",
                    "Send an " + HEADER + " header (e.g. a UUID) so a retried request can't create a duplicate");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_INVALID", HEADER + " must be 8-100 letters, digits, '-' or '_'");
        }
        return key;
    }

    /** The resource created earlier with this key, if any. Throws if the key was used for a different request. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> findPrevious(Long userId, String key, String operation, String requestHash) {
        return records.findById(new IdempotencyRecord.Key(userId, key)).map(previous -> {
            if (!previous.getOperation().equals(operation) || !previous.getRequestHash().equals(requestHash)) {
                throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                        "This " + HEADER + " was already used for a different request");
            }
            return previous.getResourceId();
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void remember(Long userId, String key, String operation, String requestHash, Long resourceId) {
        records.save(new IdempotencyRecord(userId, key, operation, requestHash, resourceId, clock.instant()));
    }
}
