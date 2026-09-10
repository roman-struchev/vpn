-- REALITY key generation was never implemented anywhere in the app (no code
-- path ever wrote reality_public_key/reality_short_ids either) — nodes only
-- worked if someone manually populated reality_public_key directly in the
-- DB and separately configured a matching private key on the node's own
-- xray out-of-band. NodeManagementService now generates a real X25519
-- keypair + short IDs on node registration, so the node's own inbound gets
-- its actual private key instead of (incorrectly) its public key.
ALTER TABLE nodes ADD COLUMN reality_private_key VARCHAR(128);
