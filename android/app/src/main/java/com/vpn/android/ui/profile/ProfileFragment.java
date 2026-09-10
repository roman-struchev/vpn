package com.vpn.android.ui.profile;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.databinding.FragmentProfileBinding;
import com.vpn.android.ui.login.LoginActivity;
import com.vpn.android.util.Async;
import com.vpn.android.vpn.XrayVpnService;

import java.util.Locale;

public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private ApiClient apiClient;
    private TokenStore tokenStore;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        tokenStore = new TokenStore(requireContext());
        apiClient = new ApiClient(tokenStore);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.logoutButton.setOnClickListener(v -> logout());
        loadProfile();
    }

    private void loadProfile() {
        Async.run(
                () -> apiClient.getProfile(),
                profile -> {
                    binding.emailText.setText(profile.email);
                    binding.balanceText.setText(getString(R.string.profile_balance,
                            String.format(Locale.US, "%.2f", profile.balanceUsdt())));
                    binding.referralText.setText(profile.referralCode);
                },
                error -> { /* keep placeholders on failure */ });
    }

    private void logout() {
        requireContext().startService(
                new Intent(requireContext(), XrayVpnService.class).setAction(XrayVpnService.ACTION_DISCONNECT));
        apiClient.logout();
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
