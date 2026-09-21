/**
 * How a row in the region list is identified, shared by the renderer (which
 * shows the list and stores the pick) and the main process (which acts on it).
 *
 * A region can offer two different things to connect to at once: our own
 * servers there, and other users' devices there (a P2P exit — the traffic
 * leaves for the internet from that person's connection, under their IP).
 * They read the same in the list, so the label cannot be the identity: the
 * key is. Mirrors SubscriptionExportService's keyFor/isP2pKey/regionFromKey
 * on the server, which produces these.
 */
const P2P_KEY_PREFIX = 'p2p:';

export function regionKeyFor(region: string, p2p: boolean): string {
  return p2p ? `${P2P_KEY_PREFIX}${region}` : region;
}

export function isP2pRegionKey(key: string | null | undefined): boolean {
  return typeof key === 'string' && key.startsWith(P2P_KEY_PREFIX);
}

/** The bare region inside a key, P2P-prefixed or not. */
export function regionFromKey(key: string): string {
  return isP2pRegionKey(key) ? key.slice(P2P_KEY_PREFIX.length) : key;
}
