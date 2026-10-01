ALTER TABLE messages ADD COLUMN acknowledged_at TIMESTAMP WITH TIME ZONE;
-- Preserve legacy delivered records for seven days after upgrading, rather than
-- guessing their delivery date or immediately discarding historical receipts.
UPDATE messages SET acknowledged_at=CURRENT_TIMESTAMP WHERE acknowledged=TRUE;
CREATE INDEX idx_delivered_retention ON messages(acknowledged,acknowledged_at,id);
