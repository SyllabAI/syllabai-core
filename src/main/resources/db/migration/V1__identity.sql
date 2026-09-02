-- V1: identity — roles, users, user_roles (Master Spec §6.1, §24)
-- The roles catalogue is a lookup table; user_roles.role is FK-validated against it.

CREATE TABLE roles (
    name        VARCHAR(16)  PRIMARY KEY,
    description VARCHAR(200) NOT NULL
);

INSERT INTO roles (name, description) VALUES
    ('STUDENT', 'Learner account — self-registered'),
    ('TEACHER', 'Teacher account — provisioned by an admin'),
    ('ADMIN',   'Administrator — full control');

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_users_email ON users (LOWER(email));

CREATE TABLE user_roles (
    user_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(16) NOT NULL REFERENCES roles (name),
    PRIMARY KEY (user_id, role)
);
