package com.claimsai.policy.infra;

import com.claimsai.policy.domain.CoverageType;
import com.claimsai.policy.domain.PolicyPort;
import com.claimsai.policy.domain.PolicySnapshot;
import com.claimsai.policy.domain.PolicySnapshot.PolicyStatus;
import com.claimsai.policy.domain.PolicySnapshot.Product;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stand-in for the policy administration system: reads the demo policy tables. Plain SQL instead of JPA
 * entities on purpose: this is someone else's data, read-only, and only the snapshot leaves the adapter.
 */
@Component
public class StubPolicyAdapter implements PolicyPort {

    private final JdbcClient jdbc;

    public StubPolicyAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PolicySnapshot> findPolicy(String policyNumber) {
        Map<CoverageType, BigDecimal> coverages = new EnumMap<>(CoverageType.class);
        jdbc.sql("SELECT coverage_type, limit_amount FROM policy_coverage WHERE policy_number = :number")
                .param("number", policyNumber)
                .query((rs, row) -> coverages.put(CoverageType.valueOf(rs.getString(1)), rs.getBigDecimal(2)))
                .list();

        return jdbc.sql("""
                        SELECT policy_number, product, holder_user_id, holder_name, status,
                               effective_from, effective_to, deductible
                        FROM policy WHERE policy_number = :number""")
                .param("number", policyNumber)
                .query((rs, row) -> new PolicySnapshot(
                        rs.getString("policy_number"),
                        Product.valueOf(rs.getString("product")),
                        rs.getObject("holder_user_id", Long.class),
                        rs.getString("holder_name"),
                        PolicyStatus.valueOf(rs.getString("status")),
                        rs.getObject("effective_from", java.time.LocalDate.class),
                        rs.getObject("effective_to", java.time.LocalDate.class),
                        rs.getBigDecimal("deductible"),
                        Map.copyOf(coverages)))
                .optional();
    }
}
