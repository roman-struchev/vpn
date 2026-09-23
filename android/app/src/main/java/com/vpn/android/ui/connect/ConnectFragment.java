package com.vpn.android.ui.connect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.util.RegionKey;
import com.vpn.android.api.model.RegionInfo;
import com.vpn.android.api.model.UserProfile;
import com.vpn.android.databinding.FragmentConnectBinding;
import com.vpn.android.util.Async;
import com.vpn.android.util.GeoLocale;
import com.vpn.android.util.WebHandoffLauncher;
import com.vpn.android.vpn.VpnStatusBus;
import com.vpn.android.vpn.XrayVpnService;
import com.vpn.android.ui.MainActivity;
import com.vpn.android.ui.login.LoginActivity;
import com.vpn.android.ui.settings.SettingsActivity;
import com.vpn.android.vpn.VpnStarter;
import com.vpn.android.vpn.state.ConnectionState;
import com.vpn.android.vpn.state.FailureReason;

public class ConnectFragment extends Fragment {

    public static final String ARG_IS_GUEST = "is_guest";
    private static final long TRAFFIC_REFRESH_INTERVAL_MS = 60_000;

    private FragmentConnectBinding binding;
    private ApiClient apiClient;
    private TokenStore tokenStore;
    private UserProfile latestProfile;
    private List<RegionInfo> availableRegions = new ArrayList<>();
    private final Map<String, Integer> regionPings = new ConcurrentHashMap<>();
    private boolean isGuest = false;
    /** What the tunnel actually connected to, as the service reports it — not necessarily what was picked. */
    private String activeRegionLabel;
    /** Whether the server had to substitute a region for the picked one. */
    private boolean regionFellBack;
    private final android.os.Handler trafficRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // Silent 60s poll while this screen is actually on screen — traffic usage
    // is otherwise only ever loaded once on fragment creation. This is also
    // why there is no "refresh" button next to the figure any more: it did
    // what this already does, a minute sooner at most.
    private final Runnable trafficRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            loadProfile();
            trafficRefreshHandler.postDelayed(this, TRAFFIC_REFRESH_INTERVAL_MS);
        }
    };

    public static ConnectFragment newInstance(boolean isGuest) {
        ConnectFragment fragment = new ConnectFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_IS_GUEST, isGuest);
        fragment.setArguments(args);
        return fragment;
    }

    public void setGuest(boolean isGuest) {
        this.isGuest = isGuest;
        if (binding != null) {
            renderGuestCard();
        }
    }

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    startVpn();
                }
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentConnectBinding.inflate(inflater, container, false);
        tokenStore = new TokenStore(requireContext());
        apiClient = new ApiClient(tokenStore);
        if (getArguments() != null) {
            isGuest = getArguments().getBoolean(ARG_IS_GUEST, false);
        }
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.connectButton.setOnClickListener(v -> onConnectButtonClicked());
        binding.regionCard.setOnClickListener(v -> showRegionPicker());
        binding.signInOrRegisterButton.setOnClickListener(v -> {
            startActivity(LoginActivity.createShowFormIntent(requireContext(), true));
        });
        renderGuestCard();

        setUpSettingsSummaryRow();

        VpnStatusBus.state.observe(getViewLifecycleOwner(), this::renderState);
        VpnStatusBus.activeRegion.observe(getViewLifecycleOwner(), region -> {
            activeRegionLabel = (region == null || region.isEmpty()) ? null : region;
            renderSelectedRegion();
        });
        VpnStatusBus.regionFallback.observe(getViewLifecycleOwner(), fellBack -> {
            regionFellBack = Boolean.TRUE.equals(fellBack);
            renderSelectedRegion();
            if (!regionFellBack) {
                binding.regionFallbackNotice.setVisibility(View.GONE);
                return;
            }
            // Two different reasons look identical to the plain server flag: a
            // genuine capacity outage (every node in the region is offline —
            // temporary, retrying later may work), vs. a previously-selected
            // region that became locked after a plan change (permanent until
            // upgrading — retrying never helps). Tell them apart client-side
            // by checking whether the current selection is a known, now-locked
            // region, same distinction the desktop client makes.
            RegionInfo selected = findRegion(tokenStore.getSelectedRegion());
            boolean becauseLocked = selected != null && !selected.accessible;
            binding.regionFallbackNotice.setText(
                    becauseLocked ? R.string.region_requires_upgrade_notice : R.string.region_unavailable_notice);
            binding.regionFallbackNotice.setVisibility(View.VISIBLE);
        });


        VpnStatusBus.failureReason.observe(getViewLifecycleOwner(),
                reason -> renderState(VpnStatusBus.state.getValue()));

        renderSelectedRegion();
        loadProfile();
        loadRegions();
        profileJustLoaded = true;
    }

    /** Set by onViewCreated's own load, so the onResume right after it does not ask again. */
    private boolean profileJustLoaded;

    /**
     * The poll runs only while this tab is in front and the app is open. It
     * used to start with the view and stop only when the view was destroyed —
     * and with the VPN service keeping the process alive, that meant a
     * profile request every minute in the background for as long as the
     * tunnel was up.
     */
    private void startTrafficPolling() {
        trafficRefreshHandler.removeCallbacks(trafficRefreshRunnable);
        trafficRefreshHandler.postDelayed(trafficRefreshRunnable, TRAFFIC_REFRESH_INTERVAL_MS);
    }

    private void stopTrafficPolling() {
        trafficRefreshHandler.removeCallbacks(trafficRefreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopTrafficPolling();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            stopTrafficPolling();
        } else if (isResumed()) {
            loadProfile();
            loadRegions();
            renderSettingsSummaryRow();
            startTrafficPolling();
        }
    }

    private void loadRegions() {
        loadRegions(null);
    }

    /**
     * @param then run after a successful load — the picker uses it to open
     *             with a list instead of "Auto" alone.
     */
    private void loadRegions(@androidx.annotation.Nullable Runnable then) {
        Async.run(
                this,
                () -> apiClient.getRegions(),
                regions -> {
                    // Every callback here has to re-check the view: switching
                    // tabs mid-load destroys it while the request is still out.
                    if (binding == null) return;
                    availableRegions = regions;
                    renderSelectedRegion();
                    loadSelectedRegionPing();
                    if (then != null) then.run();
                },
                error -> { /* keep whatever the last "Auto" default shows; not fatal to the connect flow */ });
    }

    /**
     * The one line this screen keeps of what used to be a settings card with
     * two controls and four lines of explanation (SettingsActivity has them
     * now). Shown only to a user actually in Russia, or a Russian-speaking
     * user abroad (see GeoLocale) — everyone else has no use for RU-specific
     * routing, and the row would be clutter naming a setting they will never
     * open.
     */
    private void setUpSettingsSummaryRow() {
        binding.settingsSummaryRow.setOnClickListener(
                v -> startActivity(SettingsActivity.intent(requireContext())));

        if (GeoLocale.isDeviceLocaleRussian()) {
            showSettingsSummaryRow();
            return;
        }
        Boolean originalIpIsRussia = tokenStore.getOriginalIpIsRussia();
        if (originalIpIsRussia != null) {
            if (originalIpIsRussia) showSettingsSummaryRow();
            return;
        }
        // Deliberately not revealed from this callback: the lookup is a
        // network round-trip, so the row would appear seconds after the
        // screen settled and push everything under it down. It is cached, so
        // the next launch shows it from the first frame — a row that nobody
        // is looking for yet can wait that long.
        GeoLocale.lookupOriginalIpIsRussiaAsync(tokenStore, isRussia -> { });
    }

    private void showSettingsSummaryRow() {
        if (binding == null) return;
        binding.settingsSummaryRow.setVisibility(View.VISIBLE);
        renderSettingsSummaryRow();
    }

    /** Re-read on every resume: the mode may have just been changed on the screen this row opens. */
    private void renderSettingsSummaryRow() {
        if (binding == null || binding.settingsSummaryRow.getVisibility() != View.VISIBLE) return;
        String mode = tokenStore.getRussianRoutingMode();
        int modeRes = TokenStore.RUSSIAN_ROUTING_BYPASS.equals(mode)
                ? R.string.russian_routing_bypass
                : TokenStore.RUSSIAN_ROUTING_ONLY_RU.equals(mode)
                ? R.string.russian_routing_only_ru
                : R.string.russian_routing_off;
        binding.settingsSummaryRow.setText(
                getString(R.string.settings_summary_row, getString(modeRes)));
    }

    /**
     * Measures latency to the selected region only — never to the whole list.
     *
     * Each measurement is a real TCP connection to a node's live inbound, so
     * pinging every region on every screen load put one connection per region
     * on the fleet each time (and fed the very activeConnections the load
     * indicator is derived from). The list compares regions by load and node
     * count; a latency number is only acted on for the region in use.
     */
    private void loadSelectedRegionPing() {
        String selected = tokenStore.getSelectedRegion();
        if (selected == null || selected.isBlank()) {
            return; // "Auto" — the node is whatever the server hands out, so there is nothing stable to measure
        }
        if (RegionKey.isP2p(selected)) {
            return; // A peer has no address to measure — see formatRegionRow.
        }
        if (isVpnActive(VpnStatusBus.state.getValue())) {
            // With the tunnel up this socket goes through it, so the figure
            // would be the tunnel's own round trip (or near zero, answered
            // locally by the TUN stack) — not the latency to that region. The
            // last direct measurement stays on screen instead.
            return;
        }
        Async.run(
                this,
                () -> apiClient.pingSelectedRegion(selected),
                ping -> {
                    if (ping != null && ping > 0) {
                        regionPings.put(selected, ping);
                        renderSelectedRegion();
                    }
                },
                error -> { /* non-fatal */ });
    }

    /**
     * The latency column. Invisible rather than gone while unknown, so the
     * region label beside it keeps the same width from the first frame — the
     * figure appearing must not move anything.
     */
    private void renderPing() {
        if (binding == null) return;
        String selected = tokenStore.getSelectedRegion();
        Integer ping = selected == null ? null : regionPings.get(selected);
        if (ping != null && ping > 0) {
            binding.regionPingText.setText(getString(R.string.region_ping_value, ping));
            binding.regionPingText.setVisibility(View.VISIBLE);
        } else {
            binding.regionPingText.setVisibility(View.INVISIBLE);
        }
    }

    private void renderSelectedRegion() {
        if (binding == null) return;
        renderPing();
        String selected = tokenStore.getSelectedRegion();
        if (selected == null) {
            binding.regionSelectedText.setText(
                    activeRegionLabel != null ? activeRegionLabel : getString(R.string.region_auto));
            return;
        }
        RegionInfo match = findRegion(selected);
        String pickedLabel = match != null ? formatRegionRow(match, false) : selected;
        // On a fallback this card used to claim the picked region while the
        // line directly under it said that region was unavailable — the same
        // card contradicting itself. Lead with where the traffic actually
        // goes, and keep the pick in brackets so it is clear it was not
        // forgotten.
        if (regionFellBack && activeRegionLabel != null) {
            binding.regionSelectedText.setText(
                    getString(R.string.region_in_use_instead_of, activeRegionLabel, pickedLabel));
            return;
        }
        binding.regionSelectedText.setText(pickedLabel);
    }

    /**
     * Matched on the key, not the label: a country can be listed twice, once
     * as our servers and once as P2P exits, and those are different picks.
     */
    private RegionInfo findRegion(String key) {
        for (RegionInfo r : availableRegions) {
            if (r.keyOrRegion().equals(key)) return r;
        }
        return null;
    }

    /**
     * @param withPing the picker's rows carry the latency inline, because that
     *                 list is a transient dialog. The card on the screen does
     *                 not: there the figure has a column of its own, so the
     *                 line does not reflow when it arrives (see
     *                 fragment_connect.xml and renderPing).
     */
    private String formatRegionRow(RegionInfo r, boolean withPing) {
        // Locked (out-of-plan) rows stay listed — not hidden — so a lower
        // tier can see what upgrading unlocks, but show "requires a paid
        // plan" instead of load stats that don't matter if you can't pick it.
        // A P2P exit is locked exactly the same way on a trial plan.
        if (!r.accessible) {
            String label = r.p2p ? getString(R.string.region_p2p_exit, r.region) : r.region;
            return getString(R.string.region_row_locked_format, label);
        }
        // No ping branch for P2P: a peer is reached over WebRTC and its
        // address is deliberately never handed out, so there is nothing to
        // measure — and borrowing a latency from some server in the same
        // country would be a plain lie.
        if (r.p2p) {
            return getString(R.string.region_row_p2p_format, r.region, loadLabel(r.loadLevel), r.nodeCount);
        }
        Integer ping = withPing ? regionPings.get(r.keyOrRegion()) : null;
        if (ping != null && ping > 0) {
            return getString(R.string.region_row_with_ping, r.region, ping, loadLabel(r.loadLevel), r.nodeCount);
        }
        return getString(R.string.region_row_format, r.region, loadLabel(r.loadLevel), r.nodeCount);
    }


    private String loadLabel(String loadLevel) {
        if ("HIGH".equals(loadLevel)) return getString(R.string.region_load_high);
        if ("MEDIUM".equals(loadLevel)) return getString(R.string.region_load_medium);
        return getString(R.string.region_load_low);
    }

    /**
     * The list is loaded with the screen, and the screen is no longer rebuilt
     * on every tab switch — so a load that failed (server briefly down, no
     * network yet) left the picker with nothing but "Auto" until the app was
     * restarted. An empty list is fetched again before the picker opens.
     */
    private void showRegionPicker() {
        if (availableRegions.isEmpty()) {
            loadRegions(this::openRegionPicker);
            return;
        }
        openRegionPicker();
    }

    private void openRegionPicker() {
        if (binding == null) return;
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<Boolean> accessible = new ArrayList<>();
        labels.add(getString(R.string.region_auto));
        values.add(null);
        accessible.add(true);
        for (RegionInfo r : availableRegions) {
            labels.add(formatRegionRow(r, true));
            values.add(r.keyOrRegion());
            accessible.add(r.accessible);
        }
        String current = tokenStore.getSelectedRegion();
        int checked = values.indexOf(current);
        if (checked < 0) checked = 0;

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.region_picker_title)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    // Locked regions stay in the list (so a lower tier can see what
                    // upgrading unlocks) but can't actually be picked — same
                    // "visible, not selectable" treatment as the desktop client,
                    // instead of silently letting the pick through and reassigning
                    // elsewhere later with a vague "unavailable" message.
                    if (!accessible.get(which)) {
                        Toast.makeText(requireContext(), R.string.region_locked_toast, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String picked = values.get(which);
                    tokenStore.saveSelectedRegion(picked);
                    renderSelectedRegion();
                    loadSelectedRegionPing(); // the number on the row belongs to the newly picked region now
                    // Picking a P2P row means browsing out through a
                    // stranger's phone: a home IP, at the speed of their
                    // uplink. The person lending the device gets a whole
                    // consent dialog; the person choosing one was told
                    // nothing but the letters "P2P" on the row.
                    if (RegionKey.isP2p(picked)) {
                        Toast.makeText(requireContext(), R.string.region_p2p_notice, Toast.LENGTH_LONG).show();
                    }
                    reconnectIfActive();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renderGuestCard() {
        if (binding != null) {
            binding.guestCard.setVisibility(isGuest ? View.VISIBLE : View.GONE);
        }
    }

    private void loadProfile() {
        loadProfile(null);
    }

    private void loadProfile(@androidx.annotation.Nullable java.util.function.Consumer<Boolean> onDone) {
        Async.run(
                this,
                () -> apiClient.getProfile(),
                profile -> {
                    if (binding == null) return;
                    latestProfile = profile;
                    if (profile != null) {
                        // Authoritative, and what decides how an expired
                        // session is handled (see ApiClient#recoverSession).
                        tokenStore.setDeviceAccount(profile.isGuest);
                    }
                    if (profile == null) {
                        // An empty body is not a profile. Everything below
                        // dereferences it, and the guard above used to stop
                        // one line short of bindProfile.
                        if (onDone != null) onDone.accept(false);
                        return;
                    }
                    isGuest = profile.isGuest;
                    renderGuestCard();
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).setGuestMode(profile.isGuest);
                    }
                    bindProfile(profile);
                    if (onDone != null) onDone.accept(true);
                },
                error -> {
                    // keep last-known UI; the connect flow will surface auth errors
                    if (onDone != null) onDone.accept(false);
                });
    }

    private void bindProfile(UserProfile profile) {
        if (!profile.hasActiveSubscription || profile.subscription == null) {
            // Which of trial used up / traffic used up / plan ended it is.
            binding.trafficText.setText(com.vpn.android.ui.PlanStatusText.inactive(requireContext(),
                    com.vpn.android.billing.PlanSummary.of(profile, null)));
            binding.trafficProgress.setProgress(0);
            binding.expiresText.setText("");
            return;
        }
        UserProfile.SubscriptionInfo sub = profile.subscription;
        double usedGb = sub.trafficUsedBytes / (1024.0 * 1024 * 1024);
        double limitGb = sub.trafficLimitBytes / (1024.0 * 1024 * 1024);
        binding.trafficText.setText(getString(R.string.traffic_used, usedGb, limitGb));
        int percent = limitGb > 0 ? (int) Math.min(100, (usedGb / limitGb) * 100) : 0;
        binding.trafficProgress.setProgress(percent);
        binding.expiresText.setText(sub.noExpiry
                ? getString(R.string.expires_never)
                : getString(R.string.expires_at, formatExpiresAt(sub.expiresAt)));
    }

    // The server sends a raw ISO-8601 instant ("2027-12-04T09:14:00Z") — shown
    // as-is that's an unreadable trailing "Z" and no locale formatting, and a
    // bare date reads as "good until midnight" when it may really lapse mid-day.
    private static String formatExpiresAt(String iso) {
        try {
            // MEDIUM date (4-digit year): SHORT rendered e.g. 2126-08-24 as "8/24/26", i.e. a date
            // that looks already expired.
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.parse(iso));
        } catch (Exception e) {
            return iso;
        }
    }

    private static boolean isVpnActive(ConnectionState state) {
        return state == ConnectionState.CONNECTED
                || state == ConnectionState.CONNECTING
                || state == ConnectionState.RECONNECTING;
    }

    private void onConnectButtonClicked() {
        if (isVpnActive(VpnStatusBus.state.getValue())) {
            stopVpn();
            return;
        }

        if (latestProfile != null && !latestProfile.hasActiveSubscription) {
            // The profile on screen may be up to a poll old — a plan bought on
            // the web a moment ago would be refused here. Ask again before
            // saying no.
            loadProfile(loaded -> {
                if (binding == null) return;
                if (latestProfile != null && latestProfile.hasActiveSubscription) {
                    requestVpnAndStart();
                    return;
                }
                String why = latestProfile != null
                        ? com.vpn.android.ui.PlanStatusText.inactive(requireContext(),
                                com.vpn.android.billing.PlanSummary.of(latestProfile, null))
                        : null;
                Snackbar snackbar = Snackbar.make(binding.getRoot(),
                        why != null ? why : getString(R.string.state_no_subscription), Snackbar.LENGTH_LONG);
                if (!isGuest) {
                    snackbar.setAction(R.string.get_plan_action, v -> openBillingPage());
                }
                snackbar.show();
            });
            return;
        }
        requestVpnAndStart();
    }

    private void requestVpnAndStart() {
        Intent prepareIntent = VpnService.prepare(requireContext());
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent);
        } else {
            startVpn();
        }
    }

    /**
     * Applies a just-changed region / RU-routing mode to a live tunnel. Both are
     * baked into the running session (node list, Xray rules, TUN app filter), so
     * before this the change silently did nothing until the next manual connect.
     */
    private void reconnectIfActive() {
        VpnStarter.reconnectIfActive(requireContext());
    }

    private void startVpn() {
        Intent intent = new Intent(requireContext(), XrayVpnService.class).setAction(XrayVpnService.ACTION_CONNECT);
        ContextCompat.startForegroundService(requireContext(), intent);
    }

    private void stopVpn() {
        Intent intent = new Intent(requireContext(), XrayVpnService.class).setAction(XrayVpnService.ACTION_DISCONNECT);
        requireContext().startService(intent);
    }

    // Neither this app nor the desktop client has any purchase/top-up UI of
    // its own (buying a plan happens on the web dashboard). Rather than a
    // plain marketing-site link that makes the user log in again, this mints
    // a short-lived SSO exchange code and opens the dashboard already signed
    // in (see WebHandoffLauncher / WEB_HANDOFF_RESEARCH.md). "/" is used as
    // the landing destination because the web app has no dedicated /billing
    // route yet — the pricing/top-up UI lives on its home screen.
    private void openBillingPage() {
        WebHandoffLauncher.launch(requireContext(), apiClient, binding.getRoot(), "/#tariffs");
    }

    private void renderState(ConnectionState state) {
        if (binding == null || state == null) return;
        switch (state) {
            case CONNECTED:
                binding.statusText.setText(R.string.state_connected);
                binding.statusText.setTextColor(getResources().getColor(R.color.state_connected, null));
                binding.connectButton.setText(R.string.disconnect_action);
                binding.operatorBlockedCard.setVisibility(View.GONE);
                break;
            case CONNECTING:
                binding.statusText.setText(R.string.state_connecting);
                binding.statusText.setTextColor(getResources().getColor(R.color.state_connecting, null));
                binding.connectButton.setText(R.string.disconnect_action);
                binding.operatorBlockedCard.setVisibility(View.GONE);
                break;
            case RECONNECTING:
                binding.statusText.setText(R.string.state_reconnecting);
                binding.statusText.setTextColor(getResources().getColor(R.color.state_reconnecting, null));
                binding.connectButton.setText(R.string.disconnect_action);
                binding.operatorBlockedCard.setVisibility(View.GONE);
                break;
            case OPERATOR_BLOCKED:
                binding.statusText.setText(R.string.state_operator_blocked);
                binding.statusText.setTextColor(getResources().getColor(R.color.state_operator_blocked, null));
                binding.connectButton.setText(R.string.connect_action);
                binding.operatorBlockedCard.setVisibility(View.VISIBLE);
                break;
            case ERROR:
                // The reason, not just "error": each has a different fix
                // (get a plan, sign in, wait, check the connection).
                binding.statusText.setText(errorText(VpnStatusBus.failureReason.getValue()));
                binding.statusText.setTextColor(getResources().getColor(R.color.state_error, null));
                binding.connectButton.setText(R.string.connect_action);
                binding.operatorBlockedCard.setVisibility(View.GONE);
                break;
            case DISCONNECTED:
            default:
                binding.statusText.setText(R.string.state_disconnected);
                binding.statusText.setTextColor(getResources().getColor(R.color.state_disconnected, null));
                binding.connectButton.setText(R.string.connect_action);
                binding.operatorBlockedCard.setVisibility(View.GONE);
                break;
        }
    }

    private static int errorText(FailureReason reason) {
        if (reason == null) return R.string.state_error;
        switch (reason) {
            case NO_SUBSCRIPTION: return R.string.state_error_no_subscription;
            case SESSION_EXPIRED: return R.string.state_error_session_expired;
            case NO_SERVERS: return R.string.state_error_no_servers;
            case NETWORK: return R.string.state_error_network;
            default: return R.string.state_error;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden()) {
            // Back from the background (or the web dashboard, where a plan
            // may just have been bought): the poll was paused meanwhile.
            if (!profileJustLoaded) {
                loadProfile();
                loadRegions(); // availability and load change while the app is in the background
            }
            startTrafficPolling();
        }
        profileJustLoaded = false;
        // The RU route may have just been changed on the screen this row
        // opens, and coming back to a row still naming the old mode would
        // read as "my change did not take".
        renderSettingsSummaryRow();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        trafficRefreshHandler.removeCallbacks(trafficRefreshRunnable);
        binding = null;
    }
}
