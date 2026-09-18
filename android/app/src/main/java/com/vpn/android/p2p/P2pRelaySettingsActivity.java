package com.vpn.android.p2p;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.vpn.android.BuildConfig;
import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.api.model.P2pStatusResponse;
import com.vpn.android.databinding.ActivityP2pRelaySettingsBinding;
import com.vpn.android.util.Async;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Consent + relay-mode controls for P2P relay (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.6) — reachable from ProfileFragment, not a
 * bottom-nav tab of its own (this is a secondary, opt-in feature, unlike
 * Connect/Devices/Profile).
 */
public class P2pRelaySettingsActivity extends AppCompatActivity {

    // The dashboard is hash-routed (web/src/App.tsx reads
    // window.location.hash === "#p2p-terms"), so a path-style URL is not a
    // page at all: it fell through to Spring Security's catch-all and came
    // back 403, which the browser showed as a blank screen. Desktop builds
    // the same hash form in ipc.ts ("p2p:getTermsUrl").
    private static final String TERMS_URL_FRAGMENT = "#p2p-terms";

    private ActivityP2pRelaySettingsBinding binding;
    private ApiClient apiClient;
    private TokenStore tokenStore;

    /** Web origin + the dashboard's hash route, tolerating a trailing slash on the configured base URL. */
    static String termsUrl(String webBaseUrl) {
        String base = webBaseUrl == null ? "" : webBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + TERMS_URL_FRAGMENT;
    }

    private static String termsUrl() {
        return termsUrl(BuildConfig.WEB_BASE_URL);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityP2pRelaySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        tokenStore = new TokenStore(this);
        apiClient = new ApiClient(tokenStore);

        binding.p2pTermsLink.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(termsUrl()))));
        binding.p2pApplyButton.setOnClickListener(v -> applySelection());

        // Only known once the node has actually registered at least once
        // (P2pRelayAgent#start detects+persists it on first start, "как при
        // старте ноды" per the repo owner — same one-shot geo-IP detection a
        // regular VPS node does at install time) — shown read-only, never
        // editable.
        String savedRegion = tokenStore.getP2pRelayRegion();
        if (savedRegion != null && !savedRegion.isBlank()) {
            binding.p2pRegionText.setText(getString(R.string.p2p_relay_region_format, com.vpn.android.util.GeoLocale.normalizeRegion(savedRegion)));
            binding.p2pRegionText.setVisibility(android.view.View.VISIBLE);
        }

        restoreCurrentSelection();
        loadStatus();
    }

    private void restoreCurrentSelection() {
        String mode = tokenStore.getP2pRelayMode();
        if (TokenStore.P2P_RELAY_ALWAYS.equals(mode)) {
            binding.p2pModeGroup.check(binding.p2pModeAlways.getId());
        } else if (TokenStore.P2P_RELAY_TIMED.equals(mode) && tokenStore.getP2pRelayExpiresAt() > System.currentTimeMillis()) {
            // Only the expiry is persisted, not which option produced it — more than an
            // hour left can only be the 8h option. Previously nothing was checked at all,
            // which read as "relay is off" while it was actually running.
            long remainingMs = tokenStore.getP2pRelayExpiresAt() - System.currentTimeMillis();
            binding.p2pModeGroup.check(remainingMs > TimeUnit.HOURS.toMillis(1)
                    ? binding.p2pMode8h.getId()
                    : binding.p2pMode1h.getId());
        } else {
            binding.p2pModeOff.setChecked(true);
        }
    }

    private void loadStatus() {
        Async.run(
                () -> apiClient.getP2pStatus(),
                this::renderStatus,
                error -> binding.p2pStatusText.setText(R.string.p2p_relay_status_error));
    }

    private void renderStatus(P2pStatusResponse status) {
        binding.p2pAcceptCheckbox.setChecked(status.termsAccepted);
        double creditedGb = status.bytesCreditedToday / (1024.0 * 1024 * 1024);
        double capGb = status.dailyCapBytes / (1024.0 * 1024 * 1024);
        binding.p2pStatusText.setText(getString(R.string.p2p_relay_status_format,
                String.format(Locale.US, "%.2f", creditedGb),
                String.format(Locale.US, "%.0f", capGb)));
        if (status.isGuest) {
            binding.p2pAcceptCheckbox.setEnabled(false);
            binding.p2pModeGroup.setEnabled(false);
            binding.p2pStatusText.append("\n" + getString(R.string.p2p_relay_guest_blocked));
        }
    }

    private void applySelection() {
        if (!binding.p2pAcceptCheckbox.isChecked()) {
            Toast.makeText(this, R.string.p2p_relay_must_accept, Toast.LENGTH_SHORT).show();
            return;
        }

        int checkedId = binding.p2pModeGroup.getCheckedRadioButtonId();
        String relayMode;
        long relayExpiresAt;
        if (checkedId == binding.p2pMode1h.getId()) {
            relayMode = TokenStore.P2P_RELAY_TIMED;
            relayExpiresAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
        } else if (checkedId == binding.p2pMode8h.getId()) {
            relayMode = TokenStore.P2P_RELAY_TIMED;
            relayExpiresAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(8);
        } else if (checkedId == binding.p2pModeAlways.getId()) {
            relayMode = TokenStore.P2P_RELAY_ALWAYS;
            relayExpiresAt = 0L;
        } else {
            relayMode = TokenStore.P2P_RELAY_OFF;
            relayExpiresAt = 0L;
        }

        Async.run(
                () -> {
                    // acceptTerms is idempotent server-side and cheap — always
                    // call it before minting a token/starting the service so a
                    // user who re-opens this screen after already accepting
                    // doesn't need special-casing here.
                    apiClient.acceptP2pTerms();
                    return null;
                },
                ignored -> startOrStopRelay(relayMode, relayExpiresAt),
                error -> Toast.makeText(this, R.string.p2p_relay_accept_failed, Toast.LENGTH_SHORT).show());
    }

    private void startOrStopRelay(String relayMode, long relayExpiresAt) {
        Intent serviceIntent = new Intent(this, P2pRelayService.class);
        if (TokenStore.P2P_RELAY_OFF.equals(relayMode)) {
            serviceIntent.setAction(P2pRelayService.ACTION_STOP);
            startService(serviceIntent);
        } else {
            serviceIntent.putExtra(P2pRelayService.EXTRA_RELAY_MODE, relayMode);
            serviceIntent.putExtra(P2pRelayService.EXTRA_RELAY_EXPIRES_AT, relayExpiresAt);
            ContextCompat.startForegroundService(this, serviceIntent);
        }
        Toast.makeText(this, R.string.p2p_relay_applied, Toast.LENGTH_SHORT).show();
        loadStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
