CREATE TABLE beworking.subscriptions (
    id                     SERIAL PRIMARY KEY,
    contact_id             BIGINT NOT NULL REFERENCES beworking.contact_profiles(id),
    stripe_subscription_id VARCHAR(255) NOT NULL UNIQUE,
    stripe_customer_id     VARCHAR(255),
    monthly_amount         NUMERIC(18,2) NOT NULL,
    currency               VARCHAR(3) DEFAULT 'EUR',
    cuenta                 VARCHAR(10) NOT NULL DEFAULT 'PT',
    description            VARCHAR(255) DEFAULT 'Oficina Virtual',
    vat_percent            INTEGER DEFAULT 21,
    start_date             DATE NOT NULL,
    end_date               DATE,
    active                 BOOLEAN DEFAULT TRUE,
    created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_subscriptions_contact ON beworking.subscriptions(contact_id);
CREATE INDEX idx_subscriptions_stripe  ON beworking.subscriptions(stripe_subscription_id);
CREATE INDEX idx_subscriptions_active  ON beworking.subscriptions(active) WHERE active = TRUE;
