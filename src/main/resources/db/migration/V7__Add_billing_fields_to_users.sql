-- Add billing fields to users table
ALTER TABLE beworking.users 
ADD COLUMN billing_brand VARCHAR(50),
ADD COLUMN billing_last4 VARCHAR(4),
ADD COLUMN billing_exp_month INTEGER,
ADD COLUMN billing_exp_year INTEGER,
ADD COLUMN stripe_customer_id VARCHAR(255);
