-- Update room prices: MA1A1=5€/h, MA1A2-MA1A5=10€/h
UPDATE beworking.rooms SET price_from = 5.00,  price_hour_min = 5.00  WHERE code = 'MA1A1';
UPDATE beworking.rooms SET price_from = 10.00, price_hour_min = 10.00 WHERE code = 'MA1A2';
UPDATE beworking.rooms SET price_from = 10.00, price_hour_min = 10.00 WHERE code = 'MA1A3';
UPDATE beworking.rooms SET price_from = 10.00, price_hour_min = 10.00 WHERE code = 'MA1A4';
UPDATE beworking.rooms SET price_from = 10.00, price_hour_min = 10.00 WHERE code = 'MA1A5';
