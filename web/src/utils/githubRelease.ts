export interface ReleaseAsset {
  name: string;
  browser_download_url: string;
}

export interface LatestRelease {
  tagName: string;
  htmlUrl: string;
  apk?: ReleaseAsset;
  macArm64?: ReleaseAsset;
  macIntel?: ReleaseAsset;
  windows?: ReleaseAsset;
  linux?: ReleaseAsset;
}

const REPO = 'roman-struchev/vpn';

/**
 * Fetched live from GitHub's public API instead of hardcoding a version/URL —
 * asset filenames embed the version (e.g. "Aura-VPN-0.1.3-mac-arm64.dmg"), so
 * a hardcoded link would go stale the moment the next release ships. Matches
 * assets by suffix/substring rather than assuming a fixed naming scheme holds
 * forever; any platform with no matching asset in the current release is left
 * undefined so callers can show "coming soon" instead of a dead link.
 */
export async function fetchLatestRelease(): Promise<LatestRelease | null> {
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`);
    if (!res.ok) return null;
    const data = await res.json();
    const assets: ReleaseAsset[] = Array.isArray(data.assets) ? data.assets : [];

    const find = (test: (name: string) => boolean) => assets.find((a) => test(a.name.toLowerCase()));

    return {
      tagName: data.tag_name,
      htmlUrl: data.html_url ?? `https://github.com/${REPO}/releases/latest`,
      apk: find((n) => n.endsWith('.apk')),
      macArm64: find((n) => n.endsWith('.dmg') && n.includes('arm64')),
      macIntel: find((n) => n.endsWith('.dmg') && (n.includes('x64') || n.includes('intel'))),
      windows: find((n) => n.endsWith('.exe')),
      linux: find((n) => n.endsWith('.appimage') || n.endsWith('.deb')),
    };
  } catch {
    return null;
  }
}

export function releasesPageUrl(): string {
  return `https://github.com/${REPO}/releases/latest`;
}
