CREATE TABLE account (
    id CHAR(36) NOT NULL,
    google_sub VARCHAR(255) NOT NULL,
    email VARCHAR(254) NULL,
    display_name VARCHAR(30) NULL,
    display_name_custom BOOLEAN NOT NULL,
    picture_url VARCHAR(1024) NULL,
    workspace_domain VARCHAR(253) NULL,
    state VARCHAR(40) NOT NULL,
    default_avatar VARCHAR(40) NULL,
    last_login_at TIMESTAMP(6) NULL,
    deleted_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT account_google_sub_uk UNIQUE (google_sub)
);

CREATE INDEX account_email_idx ON account (email);

CREATE TABLE account_consent (
    id CHAR(36) NOT NULL,
    account_id CHAR(36) NOT NULL,
    terms_version VARCHAR(20) NOT NULL,
    privacy_version VARCHAR(20) NOT NULL,
    agreed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX account_consent_account_id_idx ON account_consent (account_id);

CREATE TABLE auth_pending_signup (
    session_id_hash CHAR(64) NOT NULL,
    google_sub VARCHAR(255) NOT NULL,
    email VARCHAR(254) NULL,
    name VARCHAR(255) NULL,
    picture_url VARCHAR(1024) NULL,
    workspace_domain VARCHAR(253) NULL,
    invite_link_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (session_id_hash)
);

CREATE INDEX auth_pending_signup_expires_at_idx ON auth_pending_signup (expires_at);
