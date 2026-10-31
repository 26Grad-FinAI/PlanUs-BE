CREATE TABLE password_reset_code (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    code        VARCHAR(6)   NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    attempt_count INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_code_email ON password_reset_code (email);
