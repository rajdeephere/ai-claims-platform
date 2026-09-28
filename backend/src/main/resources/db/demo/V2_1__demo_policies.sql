-- Demo policies. claimant1 has an active auto and home policy; claimant2 has an active auto policy,
-- a lapsed one and a cancelled one, to show the policy-check flags.

INSERT INTO policy (policy_number, product, holder_user_id, holder_name, status, effective_from, effective_to, deductible)
SELECT v.policy_number, v.product, u.id, v.holder_name, v.status, v.effective_from::date, v.effective_to::date, v.deductible
FROM (VALUES
    ('POL-AUTO-1001', 'AUTO', 'claimant1', 'Asha Verma', 'ACTIVE',    '2026-01-01', '2027-01-01', 500.00),
    ('POL-HOME-2001', 'HOME', 'claimant1', 'Asha Verma', 'ACTIVE',    '2026-04-01', '2027-04-01', 1000.00),
    ('POL-AUTO-1002', 'AUTO', 'claimant2', 'Rohan Iyer', 'ACTIVE',    '2026-03-15', '2027-03-15', 750.00),
    ('POL-AUTO-1003', 'AUTO', 'claimant2', 'Rohan Iyer', 'LAPSED',    '2025-01-01', '2026-01-01', 750.00),
    ('POL-HOME-2002', 'HOME', 'claimant2', 'Rohan Iyer', 'CANCELLED', '2026-02-01', '2027-02-01', 1000.00)
) AS v(policy_number, product, username, holder_name, status, effective_from, effective_to, deductible)
JOIN app_user u ON u.username = v.username;

INSERT INTO policy_coverage (policy_number, coverage_type, limit_amount) VALUES
    ('POL-AUTO-1001', 'COLLISION',     25000.00),
    ('POL-AUTO-1001', 'COMPREHENSIVE', 15000.00),
    ('POL-AUTO-1001', 'GLASS',          2000.00),
    ('POL-HOME-2001', 'DWELLING',     200000.00),
    ('POL-HOME-2001', 'CONTENTS',      50000.00),
    ('POL-AUTO-1002', 'COLLISION',     20000.00),   -- no comprehensive: a theft claim is not covered
    ('POL-AUTO-1003', 'COLLISION',     20000.00),
    ('POL-HOME-2002', 'DWELLING',     150000.00);
