package com.claimsai.financials.app;

import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.identity.app.UserService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Authority limits (ADR-0005): the largest reserve or payment a person may approve alone. Always read from
 * the database at decision time, never from the token, so a lowered limit applies to the next decision.
 */
@Service
public class AuthorityService {

    private final UserService users;

    public AuthorityService(UserService users) {
        this.users = users;
    }

    public BigDecimal limitOf(Long userId) {
        return users.getActive(userId).getAuthorityLimit();
    }

    public boolean covers(Long userId, BigDecimal amount) {
        return amount.compareTo(limitOf(userId)) <= 0;
    }

    public void requireCovers(Long userId, BigDecimal amount) {
        BigDecimal limit = limitOf(userId);
        if (amount.compareTo(limit) > 0) {
            throw new BusinessRuleException("AUTHORITY_EXCEEDED", "The amount " + amount
                    + " is above your authority limit of " + limit + "; it needs someone with a higher limit");
        }
    }
}
