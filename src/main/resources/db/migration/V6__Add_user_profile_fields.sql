-- Add user profile fields to users table
ALTER TABLE beworking.users 
ADD COLUMN name VARCHAR(255),
ADD COLUMN phone VARCHAR(50),
ADD COLUMN address_line1 VARCHAR(255),
ADD COLUMN address_city VARCHAR(100),
ADD COLUMN address_country VARCHAR(100),
ADD COLUMN address_postal VARCHAR(20);
