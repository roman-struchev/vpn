package com.vpn.android.ui.connect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
import com.vpn.android.api.model.RegionInfo;
import com.vpn.android.api.model.UserProfile;
import com.vpn.android.databinding.FragmentConnectBinding;
import com.vpn.android.util.Async;
import com.vpn.android.util.WebHandoffLauncher;
import com.vpn.android.vpn.VpnStatusBus;
import com.vpn.android.vpn.XrayVpnService;
import com.vpn.android.ui.MainActivity;
import com.vpn.android.ui.login.LoginActivity;
import com.vpn.android.vpn.state.ConnectionState;

public class ConnectFragment extends Fragment {

    public static final String ARG_IS_GUEST = "is_guest";

    private FragmentConnectBinding binding;
    private ApiClient apiClient;
    private TokenStore tokenStore;
    private UserProfile latestProfile;
    private List<RegionInfo> availableRegions = new ArrayList<>();
    private final Map<String, Integer> regionPings = new ConcurrentHashMap<>();
    private boolean isGuest = false;

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

        binding.bypassRuSwitch.setChecked(tokenStore.isBypassRussianTraffic());
        binding.bypassRuSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            tokenStore.setBypassRussianTraffic(isChecked);
        });

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
        VpnStatusBus.regionFallback.observe(getViewLifecycleOwner(), fellBack ->
                binding.regionFallbackNotice.setVisibility(Boolean.TRUE.equals(fellBack) ? View.VISIBLE : View.GONE));

        renderSelectedRegion();
        loadProfile();
        loadRegions();
    }

    private void loadRegions() {
        Async.run(
                () -> apiClient.getRegions(),
                regions -> {
                    availableRegions = regions;
                    renderSelectedRegion();
                    loadRegionPings();
                },
                error -> { /* keep whatever the last "Auto" default shows; not fatal to the connect flow */ });
    }

    private void loadRegionPings() {
        Async.run(
                () -> apiClient.pingRegions(),
                pings -> {
                    if (pings != null) {
                        regionPings.putAll(pings);
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

    private RegionInfo findRegion(String region) {
        for (RegionInfo r : availableRegions) {
            if (r.region.equals(region)) return r;
        }
        return null;
    }

    private String formatRegionRow(RegionInfo r) {
        Integer ping = regionPings.get(r.region);
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
        labels.add(getString(R.string.region_auto));
        values.add(null);
        for (RegionInfo r : availableRegions) {
            labels.add(formatRegionRow(r));
            values.add(r.region);
        }
        String current = tokenStore.getSelectedRegion();
        int checked = values.indexOf(current);
        if (checked < 0) checked = 0;

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.region_picker_title)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    tokenStore.saveSelectedRegion(values.get(which));
                    renderSelectedRegion();
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
        Async.run(
                () -> apiClient.getProfile(),
                profile -> {
                    latestProfile = profile;
                    if (profile != null) {
                        isGuest = profile.isGuest;
                        renderGuestCard();
                        if (getActivity() instanceof MainActivity) {
                            ((MainActivity) getActivity()).setGuestMode(profile.isGuest);
                        }
                    }
                    bindProfile(profile);
                },
                error -> { /* keep last-known UI; the connect flow will surface auth errors */ });
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
        binding.expiresText.setText(getString(R.string.expires_at, formatExpiresAt(sub.expiresAt)));
    }

    // The server sends a raw ISO-8601 instant ("2027-12-04T09:14:00Z") — shown
    // as-is that's an unreadable trailing "Z" and no locale formatting, and a
    // bare date reads as "good until midnight" when it may really lapse mid-day.
    private static String formatExpiresAt(String iso) {
        try {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
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
        binding = null;
    }
}
