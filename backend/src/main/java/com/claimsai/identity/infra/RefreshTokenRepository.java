package com.claimsai.identity.infra;

import com.claimsai.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Ends every session of the user: used when a rotated (already used) token is presented again. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllActiveForUser(@Param("userId") Long userId, @Param("now") Instant now);

    long countByUserIdAndRevokedAtIsNull(Long userId);
}
