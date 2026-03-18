ALTER TABLE beworking.subscriptions
    ADD COLUMN billing_interval VARCHAR(15) NOT NULL DEFAULT 'month';

-- Set existing annual GT subscriptions to 'year'
UPDATE beworking.subscriptions
SET billing_interval = 'year'
WHERE stripe_subscription_id IN (
    'sub_1Svn9gDWswz0mFNgjHF0sgkt',
    'sub_1S2JIrDWswz0mFNgVd4Y55QZ',
    'sub_1S2JIqDWswz0mFNglGR1C4bk',
    'sub_1Rr4XvDWswz0mFNge5b1TPvG',
    'sub_1QnTCJDWswz0mFNgxY3Kprmq',
    'sub_1OTY0KDWswz0mFNgdKm21dCN'
);

-- Also update scheduled subscriptions (not yet started)
UPDATE beworking.subscriptions
SET billing_interval = 'year'
WHERE stripe_subscription_id IN (
    'sub_sched_1RGIm0DWswz0mFNgkWUxboHg',
    'sub_sched_1TC4WEIGBPwEtf1iwUh6iqzg'
);
