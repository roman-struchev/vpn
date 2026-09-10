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
