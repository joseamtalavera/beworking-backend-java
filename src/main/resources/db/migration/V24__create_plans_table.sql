CREATE TABLE IF NOT EXISTS beworking.plans (
    id          SERIAL PRIMARY KEY,
    plan_key    VARCHAR(30)    NOT NULL UNIQUE,
    name        VARCHAR(60)    NOT NULL,
    price       NUMERIC(10,2)  NOT NULL,
    currency    VARCHAR(3)     NOT NULL DEFAULT 'EUR',
    features    TEXT,
    popular     BOOLEAN        NOT NULL DEFAULT FALSE,
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    sort_order  INT            NOT NULL DEFAULT 0,
    created_at  TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP
);

INSERT INTO beworking.plans (plan_key, name, price, features, popular, sort_order) VALUES
    ('basic', 'Basic', 15.00, 'Domicilio fiscal y legal,Recepción de correo,Buzón digital,2 pases coworking/mes,Plataforma BeWorking,50 consultas IA/mes', FALSE, 1),
    ('pro',   'Pro',   25.00, 'Todo en Basic,5 pases coworking/mes,Salas de reuniones,Atención de llamadas,200 consultas IA/mes,Integraciones', TRUE, 2),
    ('max',   'Max',   90.00, 'Todo en Pro,Pases ilimitados,Logo en recepción,Prioridad en soporte,IA ilimitada,API y automatizaciones', FALSE, 3);
