-- V19: one-time first-admin bootstrap state (P1 product activation).
--
-- Product rule (Master Spec §6.1): self-registration always creates a STUDENT;
-- teachers/admins are provisioned by an admin. A fresh deployment therefore has
-- no path to its FIRST admin account. This migration adds the durable,
-- single-row state machine that gates exactly one first-admin claim:
--
--   PENDING  -> the claim endpoint may create the first ADMIN+TEACHER account
--               (only while zero ADMIN users exist).
--   CONSUMED -> terminal: claimed once, the endpoint refuses forever.
--   EXPIRED  -> terminal: the claim window was never used and the bootstrap
--               expiry job closed it; reopening is an explicit, auditable
--               schema change (a new migration), never a runtime action.
--
-- The state row is the ONLY authority: deleting or editing it is an operator-
-- level database action, not an application surface. No secret material is
-- stored here — the gate is (state = PENDING) AND (zero ADMIN users), enforced
-- under a row lock inside the claim transaction.

CREATE TABLE bootstrap_admin_state (
    id          INT PRIMARY KEY CHECK (id = 1),
    state       VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                CONSTRAINT ck_bas_state CHECK (state IN ('PENDING', 'CONSUMED', 'EXPIRED')),
    claimed_by  UUID REFERENCES users (id),
    claimed_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO bootstrap_admin_state (id) VALUES (1);
