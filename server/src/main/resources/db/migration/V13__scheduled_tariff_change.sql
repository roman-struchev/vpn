-- A move to a cheaper plan while a pricier one is still paid for used to go
-- through BillingService#purchaseOrRenewSubscription like any other purchase:
-- the new plan started at once and the unused rest of the pricier one was
-- simply lost. It is now scheduled instead: the plan to renew into when the
-- current period ends (QuotaEnforcementTask auto-renewal). NULL = renew the
-- same plan.
ALTER TABLE subscriptions ADD COLUMN next_tariff_id VARCHAR(32) REFERENCES tariffs(id);

-- Auto-renewal charges the balance and, when it is short, used to fail
-- silently and cut the user off. A heads-up is sent a few days before the
-- period ends when the balance won't cover it; this records that it went out,
-- so it goes out once per period (a renewal is a new row, so it resets).
ALTER TABLE subscriptions ADD COLUMN renewal_reminder_sent_at TIMESTAMPTZ;
