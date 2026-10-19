-- Onboarding: the application a business fills in to be approved for live payments (docs/adr/0028-onboarding-and-kyc.md).
-- The status the rest of the platform looks at is merchants.status; the application keeps the working data.
CREATE TABLE onboarding_applications (
    id               TEXT PRIMARY KEY,
    merchant_id      TEXT        NOT NULL UNIQUE REFERENCES merchants (id),
    data             TEXT        NOT NULL DEFAULT '{}',   -- business, company, profile and bank answers as JSON
    risk_score       INT,
    risk_reasons     TEXT,
    manual_review    BOOLEAN     NOT NULL DEFAULT FALSE,   -- a person at Cutpay must decide
    signed_name      TEXT,
    terms_accepted_at TIMESTAMPTZ,
    submitted_at     TIMESTAMPTZ,
    decided_at       TIMESTAMPTZ,
    decided_by       TEXT,                                  -- 'system' or the staff member
    decision_reason  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE persons (
    id                  TEXT PRIMARY KEY,
    merchant_id         TEXT        NOT NULL REFERENCES merchants (id),
    role                TEXT        NOT NULL CHECK (role IN ('representative', 'ubo', 'director')),
    name                TEXT        NOT NULL,
    date_of_birth       DATE        NOT NULL,
    nationality         VARCHAR(2)  NOT NULL,
    ownership_pct       INT         CHECK (ownership_pct BETWEEN 0 AND 100),
    document_type       TEXT        NOT NULL,
    document_number     TEXT        NOT NULL,
    verification_id     TEXT,
    verification_status TEXT        NOT NULL DEFAULT 'not_started'
        CHECK (verification_status IN ('not_started', 'pending', 'approved', 'rejected', 'needs_more_info')),
    verification_reason TEXT,
    screening           TEXT,                               -- 'clear', 'pep' or 'sanctions'
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX persons_merchant_idx ON persons (merchant_id);

-- Identity documents, selfies and proof of the bank account. Sample images only. They live in the database
-- (docs/adr/0027-disputes.md explains why they are not in object storage).
CREATE TABLE documents (
    id           TEXT PRIMARY KEY,
    merchant_id  TEXT        NOT NULL REFERENCES merchants (id),
    person_id    TEXT        REFERENCES persons (id) ON DELETE CASCADE,
    type         TEXT        NOT NULL CHECK (type IN ('id_front', 'id_back', 'selfie', 'bank_proof')),
    filename     TEXT        NOT NULL,
    content_type TEXT        NOT NULL,
    size         INT         NOT NULL,
    content      BYTEA       NOT NULL,
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX documents_merchant_idx ON documents (merchant_id);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
