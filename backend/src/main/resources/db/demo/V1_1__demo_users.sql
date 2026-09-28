-- Demo users (password for all: Password1!). Kept outside db/migration so a real deployment can
-- leave them out (FLYWAY_LOCATIONS=classpath:db/migration). Authority limits are set only here:
-- there is no admin UI in v1.

INSERT INTO app_user (username, password_hash, display_name, role, authority_limit) VALUES
    ('claimant1',   '$2a$10$f5qceTSVimYw8IGbPSHtT.iWN8i6dAIHBRlmEDaFwPLDMNRHeOR8i', 'Asha Verma',    'CLAIMANT',   0),
    ('claimant2',   '$2a$10$f5qceTSVimYw8IGbPSHtT.iWN8i6dAIHBRlmEDaFwPLDMNRHeOR8i', 'Rohan Iyer',    'CLAIMANT',   0),
    ('adjuster1',   '$2a$10$f5qceTSVimYw8IGbPSHtT.iWN8i6dAIHBRlmEDaFwPLDMNRHeOR8i', 'Meera Nair',    'ADJUSTER',   5000.00),
    ('adjuster2',   '$2a$10$f5qceTSVimYw8IGbPSHtT.iWN8i6dAIHBRlmEDaFwPLDMNRHeOR8i', 'Karan Shah',    'ADJUSTER',   5000.00),
    ('supervisor1', '$2a$10$f5qceTSVimYw8IGbPSHtT.iWN8i6dAIHBRlmEDaFwPLDMNRHeOR8i', 'Priya Menon',   'SUPERVISOR', 50000.00),
    ('siu1',        '$2a$10$f5qceTSVimYw8IGbPSHtT.iWN8i6dAIHBRlmEDaFwPLDMNRHeOR8i', 'Vikram Rao',    'SIU',        0);
