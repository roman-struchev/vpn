package com.vpn.android.update;

import java.util.List;

/**
 * Subset of GitHub's Releases API response (GET /repos/{owner}/{repo}/releases/latest)
 * this app reads. Field names match the API's JSON exactly for direct Gson binding.
 */
public class GithubRelease {

    public String tag_name;
    public List<Asset> assets;

    public static class Asset {
        public String name;
        public String browser_download_url;
    }
}
