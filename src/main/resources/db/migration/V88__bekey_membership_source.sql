-- Standing desk access: free Usuario Mesa users get an always-on MA1O1 grant,
-- keyed per contact (source='membership'), distinct from subscription/booking.
ALTER TABLE beworking.bekey_access DROP CONSTRAINT IF EXISTS bekey_access_source_chk;
ALTER TABLE beworking.bekey_access ADD CONSTRAINT bekey_access_source_chk
    CHECK (source IN ('subscription','booking','manual','shared','membership'));
