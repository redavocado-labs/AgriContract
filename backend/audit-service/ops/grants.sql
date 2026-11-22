-- Run as a provisioning administrator AFTER Flyway, against a fresh runtime
-- account named audit_runtime. Create its password through secret tooling.
-- No global/schema grants or inherited roles may be attached to this account.
-- The audit record is append-only: no UPDATE or DELETE, ever.
GRANT SELECT, INSERT ON audit_db.audit_record TO 'audit_runtime'@'%';
