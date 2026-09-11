package com.vpn.android.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.net.Uri;
import android.view.View;

import androidx.browser.customtabs.CustomTabsIntent;

import com.google.android.material.snackbar.Snackbar;
import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.model.WebHandoffResponse;

/**
 * Client-to-web SSO handoff (see WEB_HANDOFF_RESEARCH.md): mints a
 * short-lived, single-use exchange code via the authenticated
 * {@code POST /api/v1/auth/web-handoff} endpoint, then opens the web
 * dashboard in a Chrome Custom Tab with that code so the user lands there
 * already signed in, instead of a plain marketing-site link that makes
 * them log in again.
 *
 * No PKCE/state-param handling here on purpose — the code is short-lived
 * (~60s) and single-use, and this isn't proving identity to a third party
 * the way an OAuth flow does; see WEB_HANDOFF_RESEARCH.md §3.5.
 */
public final class WebHandoffLauncher {

    private WebHandoffLauncher() {
    }

    /**
     * @param rootView  a view attached to the current screen, used only to anchor
     *                  the failure Snackbar if minting the code or opening the
     *                  Custom Tab fails.
     * @param next      relative in-app destination on the web dashboard (e.g. "/"),
     *                  passed straight through as the {@code next} query param.
     */
    public static void launch(Context context, ApiClient apiClient, View rootView, String next) {
        Async.run(
                apiClient::requestWebHandoff,
                response -> open(context, response, next, rootView),
                error -> showFailure(rootView));
    }

    private static void open(Context context, WebHandoffResponse response, String next, View rootView) {
        if (response == null || response.webUrl == null || response.code == null) {
            showFailure(rootView);
            return;
        }
        Uri uri = Uri.parse(response.webUrl)
                .buildUpon()
                .appendQueryParameter("handoff_code", response.code)
                .appendQueryParameter("next", next)
                .build();
        try {
            CustomTabsIntent intent = new CustomTabsIntent.Builder().build();
            intent.launchUrl(context, uri);
        } catch (ActivityNotFoundException e) {
            showFailure(rootView);
        }
    }

    private static void showFailure(View rootView) {
        if (rootView != null) {
            Snackbar.make(rootView, R.string.web_handoff_error, Snackbar.LENGTH_LONG).show();
        }
    }
}
