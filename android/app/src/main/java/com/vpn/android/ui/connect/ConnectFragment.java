package com.vpn.android.ui.connect;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.api.model.UserProfile;
import com.vpn.android.databinding.FragmentConnectBinding;
import com.vpn.android.util.Async;
import com.vpn.android.vpn.VpnStatusBus;
import com.vpn.android.vpn.XrayVpnService;
import com.vpn.android.vpn.state.ConnectionState;

public class ConnectFragment extends Fragment {

    private FragmentConnectBinding binding;
    private ApiClient apiClient;
    private UserProfile latestProfile;

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
        apiClient = new ApiClient(new TokenStore(requireContext()));
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.connectButton.setOnClickListener(v -> onConnectButtonClicked());

        VpnStatusBus.state.observe(getViewLifecycleOwner(), this::renderState);
        VpnStatusBus.activeRegion.observe(getViewLifecycleOwner(), region -> {
            if (region != null && !region.isEmpty()) {
                binding.regionText.setVisibility(View.VISIBLE);
                binding.regionText.setText(getString(R.string.current_node_region, region));
            } else {
                binding.regionText.setVisibility(View.GONE);
            }
        });

        loadProfile();
    }

    private void loadProfile() {
        Async.run(
                () -> apiClient.getProfile(),
                profile -> {
                    latestProfile = profile;
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
        binding.expiresText.setText(getString(R.string.expires_at, sub.expiresAt));
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
            Snackbar.make(binding.getRoot(), R.string.state_no_subscription, Snackbar.LENGTH_LONG).show();
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
