/**
 * Parses the vless://uuid@host:port?query#remark links produced by
 * server SubscriptionExportService#buildVlessUrl. Query parameter names
 * match that method's output: encryption, security, type, path, sni, pbk, sid.
 */
export interface ParsedVlessUri {
  uuid: string;
  host: string;
  port: number;
  params: Record<string, string>;
  remark: string;
}

export function parseVlessUri(link: string): ParsedVlessUri {
  if (!link || !link.startsWith('vless://')) {
    throw new Error(`Not a vless:// link: ${link}`);
  }

  let url: URL;
  try {
    url = new URL(link);
  } catch (e) {
    throw new Error(`Malformed vless link: ${link}`);
  }

  const uuid = decodeURIComponent(url.username);
  const host = url.hostname;
  const port = url.port ? Number(url.port) : 0;
  if (!uuid || !host || !port) {
    throw new Error(`Incomplete vless link: ${link}`);
  }

  const params: Record<string, string> = {};
  url.searchParams.forEach((value, key) => {
    params[key] = value;
  });

  const remark = url.hash ? decodeURIComponent(url.hash.slice(1)) : '';

  return { uuid, host, port, params, remark };
}

export function vlessParam(uri: ParsedVlessUri, key: string, fallback: string): string {
  return uri.params[key] ?? fallback;
}

/**
 * The user-facing half of a link's remark: the server builds it as
 * "<region> · <node hostname>" (SubscriptionExportService#REMARK_SEPARATOR).
 * Only the region belongs on screen — the hostname is operator detail, and
 * with the old "-" separator it leaked into the UI as part of the region name.
 */
export function regionLabel(remark: string): string {
  const [region] = remark.split(' \u00b7 ');
  return (region || remark).trim();
}

/**
 * The first link that actually belongs to `region`, or null if none does.
 *
 * Used to ping the selected region: the server falls back to any online node
 * when the asked-for region has none (`requestedRegionAvailable: false`), so
 * taking whatever link came back would report a node in a different country as
 * this region's latency. Malformed links are skipped rather than throwing —
 * one bad entry should not cost the measurement.
 */
export function firstLinkForRegion(links: string[], region: string): ParsedVlessUri | null {
  for (const link of links) {
    try {
      const parsed = parseVlessUri(link);
      if (parsed.remark && regionLabel(parsed.remark) === region) {
        return parsed;
      }
    } catch {
      // skip a malformed link
    }
  }
  return null;
}
