-- Run as a provisioning administrator AFTER Flyway, against a fresh runtime
-- account named audit_runtime. Create its password through secret tooling.
-- No global/schema grants or inherited roles may be attached to this account.
-- The audit record is append-only: no UPDATE or DELETE, ever.
GRANT SELECT, INSERT ON audit_db.audit_record TO 'audit_runtime'@'%';
-- Lock only, holds no evidence: SELECT ... FOR UPDATE needs the UPDATE privilege in MySQL 8, so the chain mutex
-- gets its own single-row table instead of granting UPDATE on audit_record.
GRANT SELECT, UPDATE ON audit_db.audit_chain_lock TO 'audit_runtime'@'%';
