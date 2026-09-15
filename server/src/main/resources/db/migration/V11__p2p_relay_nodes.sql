-- P2P relay nodes (docs/research/P2P_RELAY_FEASIBILITY.md §8): a client
-- device (desktop/Android in relay mode, or a manually-deployed headless
-- Linux instance) can register as a node the same way a VPS node does
-- (bootstrap token + RegisterNode + heartbeat stream), just with node.type
-- = 'p2p' and no meaningful public_ip (it never accepts direct inbound
-- VLESS — a connecting client reaches it via WebRTC signaling instead, see
-- ServerMessage/AgentMessage's new p2p_signal payload).
--
-- Tariff access is decoupled from the pool lifecycle column: `pool` keeps
-- meaning ONLINE/quarantine/reserve-style lifecycle grouping exactly as
-- before; these two new independent flags say who can actually connect
-- through the node. Backfilled here to reproduce today's real access
-- exactly (paid tariffs already reach both 'paid' and 'trial' pool nodes
-- per this session's earlier paid-⊇-trial fix; trial tariffs reach only
-- 'trial' pool nodes) so nothing changes behaviorally until an admin
-- explicitly sets both flags true on a new P2P node.
ALTER TABLE nodes ADD COLUMN available_to_trial BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE nodes ADD COLUMN available_to_paid BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE nodes SET available_to_trial = TRUE WHERE pool = 'trial';
-- Every non-trial-pool node (paid, quarantine, reserve alike — quarantine/
-- reserve have no separately-tracked "which pool it belongs to when
-- healthy" today, so this defaults them to paid, matching
-- NodeManagementService#registerNode's own pool-name-to-enum fallback) plus
-- every trial-pool node (paid tariffs already reach trial-pool nodes too)
-- gets available_to_paid = true.
UPDATE nodes SET available_to_paid = TRUE WHERE pool IN ('paid', 'quarantine', 'reserve', 'trial');

-- Who a P2P node's relayed traffic should be credited to. Set at
-- registration time from the bootstrap token's own owner_user_id (below) —
-- NOT from a self-declared field in the register request, since that would
-- let anyone claim credit for someone else's relay traffic. Null for every
-- ops-minted VPS bootstrap token/node (direct/cdn), which have no owning end
-- user.
ALTER TABLE nodes ADD COLUMN owner_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL;

-- A user-minted p2p bootstrap token (docs §8.4/§8.6) is bound to the
-- requesting user at mint time (POST /api/v1/user/p2p/bootstrap-token,
-- authenticated, terms-acceptance-gated) — the one and only source of truth
-- nodes.owner_user_id is copied from at registration. Null for admin-minted
-- VPS tokens.
ALTER TABLE node_bootstrap_tokens ADD COLUMN owner_user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;

-- Relay-window state for a P2P node (docs §8.5): OFF (default, also what
-- every pre-existing VPS node gets and simply never uses), TIMED (until
-- relay_expires_at), or ALWAYS. A P2P node is only handed out to a
-- connecting client while eligible per this state — enforced server-side
-- (NodeManagementService#isEligibleForRelay), not left to client honesty.
ALTER TABLE nodes ADD COLUMN relay_mode VARCHAR(16) NOT NULL DEFAULT 'OFF';
ALTER TABLE nodes ADD COLUMN relay_expires_at TIMESTAMPTZ;

-- User-level P2P relay consent + daily-credit bookkeeping (docs §8.6, §8.2).
-- p2p_relay_terms_accepted_at gates the feature entirely (null = never
-- shown/accepted the consent screen, so relay mode can't be enabled) —
-- authenticated users only, checked alongside the existing isGuest signal.
ALTER TABLE users ADD COLUMN p2p_relay_terms_accepted_at TIMESTAMPTZ;

-- One row per credited relay session, after the two independent
-- self-reports (relaying peer + connecting client) agree within tolerance
-- (P2pRelayAccountingService). Deliberately NOT a BalanceEntry: the credit
-- here is byte-denominated (a traffic-limit bump on the relaying user's own
-- subscription), not a USDT amount — every tariff's real $/GB price differs
-- enough (Basic $0.05/GB vs Pro $0.02/GB, from V1's seed data) that picking
-- one as "the" conversion rate would be an arbitrary product call this
-- migration doesn't make; crediting traffic directly sidesteps needing one
-- at all. bytes_credited is always half of bytes_relayed (see
-- P2pRelayAccountingService.CREDIT_RATE) — stored explicitly rather than
-- recomputed so a future rate change never silently reinterprets history.
CREATE TABLE p2p_relay_credits (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id VARCHAR(64) NOT NULL UNIQUE,
    relay_node_id BIGINT REFERENCES nodes(id) ON DELETE SET NULL,
    bytes_relayed BIGINT NOT NULL,
    bytes_credited BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Daily-cap enforcement (50GB/day/user, docs §8.2) sums bytes_credited for
-- one user across "today" — this index is exactly that query's access path.
CREATE INDEX idx_p2p_relay_credits_user_created ON p2p_relay_credits(user_id, created_at);
