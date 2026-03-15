-- Restore MA1A1 price to 5€/h
UPDATE beworking.rooms SET price_from = 5.00, price_hour_min = 5.00 WHERE code = 'MA1A1';
