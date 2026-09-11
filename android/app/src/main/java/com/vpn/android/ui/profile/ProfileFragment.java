package com.vpn.android.ui.profile;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vpn.android.BuildConfig;
import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.databinding.FragmentProfileBinding;
import com.vpn.android.ui.login.LoginActivity;
import com.vpn.android.util.Async;
import com.vpn.android.util.WebHandoffLauncher;
import com.vpn.android.vpn.XrayVpnService;

import java.util.Locale;

public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private ApiClient apiClient;
    private TokenStore tokenStore;
    private String referralLink = "";

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
        binding.signInExistingButton.setOnClickListener(v -> signInWithExistingAccount());
        binding.copyReferralButton.setOnClickListener(v -> copyReferralLink());
        binding.shareReferralButton.setOnClickListener(v -> shareReferralLink());
        binding.manageBillingButton.setOnClickListener(v -> openBillingPage());
        loadProfile();
    }

    private void loadProfile() {
        Async.run(
                () -> apiClient.getProfile(),
                profile -> {
                    binding.emailText.setText(profile.email);
                    binding.balanceText.setText(getString(R.string.profile_balance,
                            String.format(Locale.US, "%.2f", profile.balanceUsdt())));
                    // Show the full shareable https link, not just the bare code — a
                    // friend on any platform can open it directly (the code stays
                    // visible underneath for anyone who wants to type it in manually).
                    referralLink = profile.shareableReferralLink(BuildConfig.WEB_BASE_URL);
                    binding.referralText.setText(referralLink);
                    binding.referralCodeText.setText(
                            getString(R.string.profile_referral_code,
                                    profile.referralCode == null ? "" : profile.referralCode));
                    binding.referralStatsText.setText(
                            getString(R.string.profile_referral_stats,
                                    profile.referralCount,
                                    profile.referralEarningsUsdtMicro / 1_000_000.0));
                },
                error -> { /* keep placeholders on failure */ });

    }

    private void copyReferralLink() {
        if (referralLink.isEmpty()) return;
        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.profile_referral), referralLink));
            Toast.makeText(requireContext(), R.string.profile_referral_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareReferralLink() {
        if (referralLink.isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, referralLink);
        startActivity(Intent.createChooser(share, getString(R.string.profile_referral_share_title)));
    }

    // Persistent entry point into the web dashboard's billing/top-up UI
    // (UX_REVIEW §B) — not just the dead-end "no subscription" state on the
    // connect screen. Opens already signed in via the same SSO handoff as
    // ConnectFragment's "Get a plan" action; "/" is used as the landing
    // destination because the web app has no dedicated /billing route yet.
    private void openBillingPage() {
        WebHandoffLauncher.launch(requireContext(), apiClient, binding.getRoot(), "/");
    }

    private void logout() {
        requireContext().startService(
                new Intent(requireContext(), XrayVpnService.class).setAction(XrayVpnService.ACTION_DISCONNECT));
        // apiClient.logout() now does a best-effort network call to revoke this
        // device before clearing the local session, so it can't run on the main
        // thread. It never throws (the revoke failure is swallowed internally),
        // but goToLogin() runs from both callbacks regardless, to be safe.
        Async.run(
                () -> {
                    apiClient.logout();
                    return null;
                },
                result -> goToLogin(),
                error -> goToLogin());
    }

    private void goToLogin() {
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    /**
     * For a user who wants to actually sign in with an existing account (to
     * sync across devices, or after registering elsewhere) instead of the
     * auto-created trial device account they're currently on. Sends them to
     * LoginActivity's explicit form-showing mode without clearing the
     * current session first — unlike logout(), this doesn't touch the
     * stored token/device UUID; a successful login/register there simply
     * overwrites the token in place.
     */
    private void signInWithExistingAccount() {
        requireContext().startService(
                new Intent(requireContext(), XrayVpnService.class).setAction(XrayVpnService.ACTION_DISCONNECT));
        Intent intent = LoginActivity.createShowFormIntent(requireContext());
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
