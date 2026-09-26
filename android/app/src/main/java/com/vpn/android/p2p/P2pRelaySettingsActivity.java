package com.vpn.android.p2p;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
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

    /**
     * Whether this account has already accepted the terms — seeded from the
     * local cache so it is known before the status call returns, then
     * refreshed from the server's answer.
     */
    private boolean termsAccepted;

    /** The consent dialog while it is up — kept so onResume does not redraw the screen underneath it, and so it is dismissed with the activity. */
    private AlertDialog consentDialog;

    /**
     * Whether turning the relay to {@code relayMode} still needs the user to
     * be asked. Consent is one-time and only ever about *starting* to relay:
     * switching relaying off used to be refused until the user re-ticked an
     * acceptance checkbox, which is backwards — withdrawing from the feature
     * can never require agreeing to it.
     */
    static boolean needsConsent(String relayMode, boolean alreadyAccepted) {
        return !TokenStore.P2P_RELAY_OFF.equals(relayMode) && !alreadyAccepted;
    }

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
        termsAccepted = tokenStore.isP2pTermsAccepted();

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
        showUnsupportedNetwork();
        loadStatus();
    }

    /**
     * Which timed option is the running window's, given the duration that was
     * chosen (0 when unknown) and how much of it is left.
     *
     * The duration is the real answer; the remaining-time guess is only the
     * fallback for a window started by a build that did not persist one yet,
     * and it is wrong for exactly the case it cannot see — an 8h window in its
     * last hour looks like a 1h window, so the screen re-selected "1 hour"
     * while an 8-hour one was running.
     */
    static boolean isEightHourWindow(long durationMs, long remainingMs) {
        if (durationMs > 0) {
            return durationMs > TimeUnit.HOURS.toMillis(1);
        }
        return remainingMs > TimeUnit.HOURS.toMillis(1);
    }

    private void restoreCurrentSelection() {
        String mode = tokenStore.getP2pRelayMode();
        long expiresAt = tokenStore.getP2pRelayExpiresAt();
        binding.p2pExpiresText.setVisibility(android.view.View.GONE);
        if (TokenStore.P2P_RELAY_ALWAYS.equals(mode)) {
            binding.p2pModeGroup.check(binding.p2pModeAlways.getId());
        } else if (TokenStore.P2P_RELAY_TIMED.equals(mode) && expiresAt > System.currentTimeMillis()) {
            binding.p2pModeGroup.check(
                    isEightHourWindow(tokenStore.getP2pRelayDurationMs(), expiresAt - System.currentTimeMillis())
                            ? binding.p2pMode8h.getId()
                            : binding.p2pMode1h.getId());
            // Without this, a timed window gave no way to tell how much of it
            // was left — the desktop client has shown it all along.
            binding.p2pExpiresText.setText(getString(R.string.p2p_relay_expires_at_format,
                    DateFormat.getTimeFormat(this).format(new java.util.Date(expiresAt))));
            binding.p2pExpiresText.setVisibility(android.view.View.VISIBLE);
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
        // The server is the authority on consent (it may have been given on
        // another device), so cache its answer — but never un-cache an
        // acceptance this session just made and the status call predates.
        termsAccepted = termsAccepted || status.termsAccepted;
        tokenStore.saveP2pTermsAccepted(termsAccepted);
        double creditedGb = status.bytesCreditedToday / (1024.0 * 1024 * 1024);
        double capGb = status.dailyCapBytes / (1024.0 * 1024 * 1024);
        binding.p2pStatusText.setText(getString(R.string.p2p_relay_status_format,
                String.format(Locale.US, "%.2f", creditedGb),
                String.format(Locale.US, "%.0f", capGb)));
        if (status.isGuest) {
            // RadioGroup#setEnabled does not disable its children, so the
            // options stayed tappable for a guest; disable the apply button —
            // the one control that actually starts anything — as well.
            for (int i = 0; i < binding.p2pModeGroup.getChildCount(); i++) {
                binding.p2pModeGroup.getChildAt(i).setEnabled(false);
            }
            binding.p2pApplyButton.setEnabled(false);
            binding.p2pStatusText.append("\n" + getString(R.string.p2p_relay_guest_blocked));
        }
    }

    /** The picked relay window: its mode, when it ends, and how long it was chosen to run (0 when it has no duration). */
    private record Selection(String mode, long expiresAt, long durationMs) {
        static Selection timed(long durationMs) {
            return new Selection(TokenStore.P2P_RELAY_TIMED, System.currentTimeMillis() + durationMs, durationMs);
        }
    }

    private void applySelection() {
        int checkedId = binding.p2pModeGroup.getCheckedRadioButtonId();
        Selection selection;
        if (checkedId == binding.p2pMode1h.getId()) {
            selection = Selection.timed(TimeUnit.HOURS.toMillis(1));
        } else if (checkedId == binding.p2pMode8h.getId()) {
            selection = Selection.timed(TimeUnit.HOURS.toMillis(8));
        } else if (checkedId == binding.p2pModeAlways.getId()) {
            selection = new Selection(TokenStore.P2P_RELAY_ALWAYS, 0L, 0L);
        } else {
            selection = new Selection(TokenStore.P2P_RELAY_OFF, 0L, 0L);
        }

        if (needsConsent(selection.mode(), termsAccepted)) {
            confirmConsentThen(selection);
        } else {
            startOrStopRelay(selection);
        }
    }

    /**
     * The one-time consent question, asked in a dialog at the moment it
     * matters instead of living on the screen as a checkbox. "Read the terms"
     * opens the web page without answering the question — an AlertDialog
     * button dismisses the dialog by default, which would make reading the
     * terms cancel the very choice they are there to inform, so that one
     * button's listener is replaced after show() to keep the dialog up.
     */
    private void confirmConsentThen(Selection selection) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.p2p_relay_consent_title)
                .setMessage(R.string.p2p_relay_consent_message)
                .setPositiveButton(R.string.p2p_relay_consent_accept,
                        (d, which) -> acceptTermsThen(selection))
                .setNeutralButton(R.string.p2p_relay_terms_link, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(termsUrl())))));
        consentDialog = dialog;
        dialog.show();
    }

    private void acceptTermsThen(Selection selection) {
        Async.run(
                () -> {
                    apiClient.acceptP2pTerms();
                    return null;
                },
                ignored -> {
                    // Remembered locally too, so the question is never asked a
                    // second time — not even before the next status call lands.
                    termsAccepted = true;
                    tokenStore.saveP2pTermsAccepted(true);
                    startOrStopRelay(selection);
                },
                error -> Toast.makeText(this, R.string.p2p_relay_accept_failed, Toast.LENGTH_SHORT).show());
    }

    private void startOrStopRelay(Selection selection) {
        if (TokenStore.P2P_RELAY_OFF.equals(selection.mode())) {
            tokenStore.saveP2pRelayUnsupportedNetwork(null);
            launchRelay(selection);
            return;
        }
        // Checked here too, not only in the service, so the answer comes on
        // this screen, right after the tap, instead of as a notification.
        binding.p2pApplyButton.setEnabled(false);
        Async.run(
                () -> NatCheck.check(NatCheck.servers(), 3000),
                verdict -> {
                    binding.p2pApplyButton.setEnabled(true);
                    if (NatCheck.refuses(verdict)) {
                        tokenStore.saveP2pRelayUnsupportedNetwork(verdict.name());
                        showUnsupportedNetwork();
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.p2p_relay_unsupported_title)
                                .setMessage(P2pRelayService.unsupportedNetworkText(verdict))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        return;
                    }
                    launchRelay(selection);
                },
                error -> {
                    binding.p2pApplyButton.setEnabled(true);
                    launchRelay(selection);
                });
    }

    /** Shows why relaying was last refused here, or nothing. */
    private void showUnsupportedNetwork() {
        String saved = tokenStore.getP2pRelayUnsupportedNetwork();
        if (saved == null) {
            binding.p2pUnsupportedText.setVisibility(android.view.View.GONE);
            return;
        }
        binding.p2pUnsupportedText.setText(getString(R.string.p2p_relay_unsupported_title) + "\n"
                + getString(P2pRelayService.unsupportedNetworkText(saved)));
        binding.p2pUnsupportedText.setVisibility(android.view.View.VISIBLE);
    }

    private void launchRelay(Selection selection) {
        // Which option produced the window, so re-opening this screen can
        // re-select it exactly (see isEightHourWindow). The service persists
        // the mode and the expiry itself; only the duration is ours to keep.
        tokenStore.saveP2pRelayDurationMs(selection.durationMs());

        Intent serviceIntent = new Intent(this, P2pRelayService.class);
        if (TokenStore.P2P_RELAY_OFF.equals(selection.mode())) {
            serviceIntent.setAction(P2pRelayService.ACTION_STOP);
            startService(serviceIntent);
        } else {
            serviceIntent.putExtra(P2pRelayService.EXTRA_RELAY_MODE, selection.mode());
            serviceIntent.putExtra(P2pRelayService.EXTRA_RELAY_EXPIRES_AT, selection.expiresAt());
            ContextCompat.startForegroundService(this, serviceIntent);
        }
        Toast.makeText(this, R.string.p2p_relay_applied, Toast.LENGTH_SHORT).show();
        // Applying is the whole point of this screen, so it closes — staying
        // on it left the user to press back and wonder whether anything had
        // happened. The state is on the profile row they came from.
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The service turns a TIMED window off on its own once it elapses, and
        // the relay can also be stopped from its notification — so what was on
        // screen when the user left it may no longer be true. Not while the
        // consent dialog is up, though: the user got here from it (reading the
        // terms in a browser), and resetting the radio underneath would
        // discard the very choice they are about to confirm.
        if (consentDialog != null && consentDialog.isShowing()) {
            return;
        }
        restoreCurrentSelection();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (consentDialog != null) {
            consentDialog.dismiss(); // a dialog outliving its activity leaks its window
            consentDialog = null;
        }
        binding = null;
    }
}
