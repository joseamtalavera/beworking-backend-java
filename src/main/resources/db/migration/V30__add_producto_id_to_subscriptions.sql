ALTER TABLE beworking.subscriptions
    ADD COLUMN producto_id BIGINT;

ALTER TABLE beworking.subscriptions
    ADD CONSTRAINT fk_subscriptions_producto
    FOREIGN KEY (producto_id) REFERENCES beworking.productos(id);
