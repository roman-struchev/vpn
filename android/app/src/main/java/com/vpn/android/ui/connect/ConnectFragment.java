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
import com.vpn.android.vpn.state.ConnectionState;

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
    private final android.os.Handler trafficRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // Silent 60s background poll while this screen is visible, on top of the
    // manual refreshUsageButton — traffic usage otherwise only ever loaded
    // once on fragment creation, same fix as the desktop client's.
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

        setUpRussianRoutingControl();

        binding.autoBootSwitch.setChecked(tokenStore.isAutoConnectOnBoot());
        binding.autoBootSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            tokenStore.setAutoConnectOnBoot(isChecked);
        });

        VpnStatusBus.state.observe(getViewLifecycleOwner(), this::renderState);
        VpnStatusBus.activeRegion.observe(getViewLifecycleOwner(), region -> {
            if (region != null && !region.isEmpty()) {
                binding.regionText.setVisibility(View.VISIBLE);
                binding.regionText.setText(getString(R.string.current_node_region, region));
            } else {
                binding.regionText.setVisibility(View.GONE);
            }
        });
        VpnStatusBus.regionFallback.observe(getViewLifecycleOwner(), fellBack -> {
            if (!Boolean.TRUE.equals(fellBack)) {
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

        binding.refreshUsageButton.setOnClickListener(v -> refreshProfileManually());

        renderSelectedRegion();
        loadProfile();
        loadRegions();
        trafficRefreshHandler.postDelayed(trafficRefreshRunnable, TRAFFIC_REFRESH_INTERVAL_MS);
    }

    private void loadRegions() {
        Async.run(
                () -> apiClient.getRegions(),
                regions -> {
                    availableRegions = regions;
                    renderSelectedRegion();
                    loadSelectedRegionPing();
                    updateRussianRoutingWarning();
                },
                error -> { /* keep whatever the last "Auto" default shows; not fatal to the connect flow */ });
    }

    /**
     * Shown only to a user actually in Russia, or a Russian-speaking user
     * abroad (see GeoLocale) — everyone else has no use for RU-specific
     * routing and the control would just be confusing clutter.
     */
    private void setUpRussianRoutingControl() {
        if (GeoLocale.isDeviceLocaleRussian()) {
            showRussianRoutingControl();
            return;
        }
        Boolean originalIpIsRussia = tokenStore.getOriginalIpIsRussia();
        if (originalIpIsRussia != null) {
            if (originalIpIsRussia) showRussianRoutingControl();
            return;
        }
        GeoLocale.lookupOriginalIpIsRussiaAsync(tokenStore, isRussia -> {
            if (isRussia && binding != null) showRussianRoutingControl();
        });
    }

    private void showRussianRoutingControl() {
        if (binding == null) return;
        binding.russianRoutingContainer.setVisibility(View.VISIBLE);

        String mode = tokenStore.getRussianRoutingMode();
        setRussianRoutingModeUi(mode);

        binding.russianRoutingToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String newMode = checkedId == binding.russianRoutingBypassButton.getId()
                    ? TokenStore.RUSSIAN_ROUTING_BYPASS
                    : checkedId == binding.russianRoutingOnlyRuButton.getId()
                    ? TokenStore.RUSSIAN_ROUTING_ONLY_RU
                    : TokenStore.RUSSIAN_ROUTING_OFF;
            tokenStore.setRussianRoutingMode(newMode);
            setRussianRoutingModeUi(newMode);
            reconnectIfActive();
        });
    }

    private void setRussianRoutingModeUi(String mode) {
        int buttonId = TokenStore.RUSSIAN_ROUTING_BYPASS.equals(mode)
                ? binding.russianRoutingBypassButton.getId()
                : TokenStore.RUSSIAN_ROUTING_ONLY_RU.equals(mode)
                ? binding.russianRoutingOnlyRuButton.getId()
                : binding.russianRoutingOffButton.getId();
        binding.russianRoutingToggleGroup.check(buttonId);

        int descRes = TokenStore.RUSSIAN_ROUTING_BYPASS.equals(mode)
                ? R.string.russian_routing_bypass_desc
                : TokenStore.RUSSIAN_ROUTING_ONLY_RU.equals(mode)
                ? R.string.russian_routing_only_ru_desc
                : R.string.russian_routing_off_desc;
        binding.russianRoutingDescText.setText(descRes);
        updateRussianRoutingWarning();
    }

    /** Only RU-only mode needs an actual Russia-located node to do anything useful. */
    private void updateRussianRoutingWarning() {
        if (binding == null) return;
        boolean isOnlyRu = TokenStore.RUSSIAN_ROUTING_ONLY_RU.equals(tokenStore.getRussianRoutingMode());
        // Our own servers only: this warning is about what the mode picks on
        // its own (XrayVpnService#resolveConnectRegion), not about what the
        // user could pick by hand.
        boolean hasAccessibleRussianRegion = availableRegions.stream()
                .anyMatch(r -> r.accessible && !r.p2p && r.region != null && r.region.toLowerCase().contains("russia"));
        binding.russianRoutingWarningText.setVisibility(
                isOnlyRu && !hasAccessibleRussianRegion ? View.VISIBLE : View.GONE);
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
        Async.run(
                () -> apiClient.pingSelectedRegion(selected),
                ping -> {
                    if (ping != null && ping > 0) {
                        regionPings.put(selected, ping);
                        renderSelectedRegion();
                    }
                },
                error -> { /* non-fatal */ });
    }

    private void renderSelectedRegion() {
        String selected = tokenStore.getSelectedRegion();
        if (selected == null) {
            binding.regionSelectedText.setText(R.string.region_auto);
            return;
        }
        RegionInfo match = findRegion(selected);
        binding.regionSelectedText.setText(match != null ? formatRegionRow(match) : selected);
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

    private String formatRegionRow(RegionInfo r) {
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
        Integer ping = regionPings.get(r.keyOrRegion());
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

    private void showRegionPicker() {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<Boolean> accessible = new ArrayList<>();
        labels.add(getString(R.string.region_auto));
        values.add(null);
        accessible.add(true);
        for (RegionInfo r : availableRegions) {
            labels.add(formatRegionRow(r));
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
                    tokenStore.saveSelectedRegion(values.get(which));
                    renderSelectedRegion();
                    loadSelectedRegionPing(); // the number on the row belongs to the newly picked region now
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

    // Minimum time the spinner stays visible: the profile call is often fast enough that the
    // button would otherwise just flicker, which reads as "the tap did nothing".
    private static final long MANUAL_REFRESH_MIN_SPINNER_MS = 600;

    /** Refresh button: spinner in place of the button while loading, plus explicit success/failure feedback. */
    private void refreshProfileManually() {
        if (binding == null || binding.refreshUsageProgress.getVisibility() == View.VISIBLE) return;
        binding.refreshUsageButton.setVisibility(View.INVISIBLE);
        binding.refreshUsageButton.setEnabled(false);
        binding.refreshUsageProgress.setVisibility(View.VISIBLE);
        long startedAt = android.os.SystemClock.elapsedRealtime();
        loadProfile(success -> {
            long remaining = MANUAL_REFRESH_MIN_SPINNER_MS - (android.os.SystemClock.elapsedRealtime() - startedAt);
            trafficRefreshHandler.postDelayed(() -> {
                if (binding == null) return;
                binding.refreshUsageProgress.setVisibility(View.GONE);
                binding.refreshUsageButton.setVisibility(View.VISIBLE);
                binding.refreshUsageButton.setEnabled(true);
                Toast.makeText(requireContext(),
                        success ? R.string.refresh_usage_done : R.string.refresh_usage_failed,
                        Toast.LENGTH_SHORT).show();
            }, Math.max(0, remaining));
        });
    }

    private void loadProfile(@androidx.annotation.Nullable java.util.function.Consumer<Boolean> onDone) {
        Async.run(
                () -> apiClient.getProfile(),
                profile -> {
                    if (binding == null) return;
                    latestProfile = profile;
                    if (profile != null) {
                        isGuest = profile.isGuest;
                        renderGuestCard();
                        if (getActivity() instanceof MainActivity) {
                            ((MainActivity) getActivity()).setGuestMode(profile.isGuest);
                        }
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
            binding.trafficText.setText(R.string.state_no_subscription);
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

    private void onConnectButtonClicked() {
        ConnectionState state = VpnStatusBus.state.getValue();
        boolean isActive = state == ConnectionState.CONNECTED
                || state == ConnectionState.CONNECTING
                || state == ConnectionState.RECONNECTING;

        if (isActive) {
            stopVpn();
            return;
        }

        if (latestProfile != null && !latestProfile.hasActiveSubscription) {
            Snackbar snackbar = Snackbar.make(binding.getRoot(), R.string.state_no_subscription, Snackbar.LENGTH_LONG);
            if (!isGuest) {
                snackbar.setAction(R.string.get_plan_action, v -> openBillingPage());
            }
            snackbar.show();
            return;
        }

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
        ConnectionState state = VpnStatusBus.state.getValue();
        if (state != ConnectionState.CONNECTED
                && state != ConnectionState.CONNECTING
                && state != ConnectionState.RECONNECTING) {
            return;
        }
        Intent intent = new Intent(requireContext(), XrayVpnService.class).setAction(XrayVpnService.ACTION_RECONNECT);
        ContextCompat.startForegroundService(requireContext(), intent);
        Toast.makeText(requireContext(), R.string.reconnecting_with_new_settings, Toast.LENGTH_SHORT).show();
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
        WebHandoffLauncher.launch(requireContext(), apiClient, binding.getRoot(), "/");
    }

    private void renderState(ConnectionState state) {
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
                binding.statusText.setText(R.string.state_error);
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        trafficRefreshHandler.removeCallbacks(trafficRefreshRunnable);
        binding = null;
    }
}
