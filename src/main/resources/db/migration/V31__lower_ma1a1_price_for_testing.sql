-- Lower MA1A1 price to 0.50€/h for testing
UPDATE beworking.rooms SET price_from = 0.50, price_hour_min = 0.50 WHERE code = 'MA1A1';
