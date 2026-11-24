-- D96: when the BF-04 reminders were written, so each milestone is reminded once. buyer_receive_reminded_at: the buyer
-- had not received within buyerReceiveWindowDays of the seller's first declaration (design §3.2).
-- funding_reminded_at: fundingReminderBusinessDays before funding_scheduled_date. Set once by a claim
-- (UPDATE ... WHERE ... IS NULL), never cleared. All additive.
ALTER TABLE milestone
    ADD COLUMN buyer_receive_reminded_at TIMESTAMP(6) NULL,
    ADD COLUMN funding_reminded_at       TIMESTAMP(6) NULL;
