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
import com.vpn.android.p2p.P2pRelaySettingsActivity;
import com.vpn.android.ui.login.LoginActivity;
import com.vpn.android.util.Async;
import com.vpn.android.util.WebHandoffLauncher;
import com.vpn.android.vpn.XrayVpnService;

import java.util.Locale;
import java.util.List;
import java.util.Date;
import java.time.Instant;
import java.text.DateFormat;
import com.vpn.android.billing.PlanSummary;
import com.vpn.android.api.model.UserProfile;
import com.vpn.android.api.model.TariffInfo;

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
        binding.changePlanButton.setOnClickListener(v -> openPlansPage());
        binding.settingsButton.setOnClickListener(v ->
                startActivity(com.vpn.android.ui.settings.SettingsActivity.intent(requireContext())));
        binding.p2pRelayButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), P2pRelaySettingsActivity.class)));
        // The first load comes from onResume, which always follows this.
    }

    @Override
    public void onResume() {
        super.onResume();
        // "Change plan"/"Manage billing" hand off to the web dashboard in an
        // external browser, so a plan bought there is only visible here once
        // this screen asks again — without this, a user came back from a
        // successful purchase to a card still showing their old plan.
        loadProfile();
    }

    private void loadProfile() {
        Async.run(
                this,
                // Both in one background pass: the profile carries the
                // subscription, the catalogue turns its bare tariffId into a
                // name, a price and a device allowance. The catalogue is
                // best-effort — PlanSummary falls back to the id rather than
                // leaving the card empty if this half fails.
                () -> {
                    UserProfile profile = apiClient.getProfile();
                    List<TariffInfo> tariffs;
                    try {
                        tariffs = apiClient.getTariffs();
                    } catch (Exception e) {
                        tariffs = null;
                    }
                    return new ProfileWithPlan(profile, PlanSummary.of(profile, tariffs));
                },
                loaded -> {
                    if (binding == null) {
                        return; // the screen was left while this was in flight
                    }
                    UserProfile profile = loaded.profile;
                    renderPlan(loaded.plan);
                    binding.emailText.setText(profile.email);
                    binding.balanceText.setText(getString(R.string.profile_balance,
                            String.format(Locale.US, "%.2f", profile.balanceUsdt())));
                    // Kept, but no longer printed on the screen: the copy
                    // and share buttons are what anyone actually does with a
                    // link, and a full https URL on its own line was the
                    // widest thing on this tab. The code below it is the part
                    // people read out loud.
                    referralLink = profile.shareableReferralLink(BuildConfig.WEB_BASE_URL);
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

    /** Carries both halves of one background load — see loadProfile. */
    private static final class ProfileWithPlan {
        final UserProfile profile;
        final PlanSummary plan;

        ProfileWithPlan(UserProfile profile, PlanSummary plan) {
            this.profile = profile;
            this.plan = plan;
        }
    }

    /**
     * Renders the plan card. Every line is omitted rather than shown empty
     * when the underlying fact is unknown: a plan with no expiry says so, a
     * catalogue that could not be loaded simply drops the device allowance,
     * and an account with no plan at all gets a "choose a plan" state instead
     * of a card full of blanks.
     */
    private void renderPlan(PlanSummary plan) {
        if (!plan.hasSubscription()) {
            binding.planNameText.setText(R.string.profile_plan_none);
            binding.planTrafficText.setVisibility(View.GONE);
            binding.planTrafficProgress.setVisibility(View.GONE);
            binding.planExpiryText.setVisibility(View.GONE);
            binding.changePlanButton.setText(R.string.profile_change_plan_choose);
            return;
        }

        binding.changePlanButton.setText(R.string.profile_change_plan);
        String planName = localizedPlanName(plan);
        binding.planNameText.setText(plan.isFree()
                ? getString(R.string.profile_plan_free, planName)
                : getString(R.string.profile_plan_paid, planName, plan.monthlyPriceUsdt()));

        binding.planTrafficText.setVisibility(View.VISIBLE);
        if (plan.trafficLimitBytes() > 0) {
            binding.planTrafficText.setText(getString(R.string.profile_plan_traffic,
                    formatBytes(plan.trafficUsedBytes()), formatBytes(plan.trafficLimitBytes())));
            binding.planTrafficProgress.setVisibility(View.VISIBLE);
            binding.planTrafficProgress.setProgress(plan.trafficPercent());
        } else {
            binding.planTrafficText.setText(getString(R.string.profile_plan_traffic_unlimited,
                    formatBytes(plan.trafficUsedBytes())));
            binding.planTrafficProgress.setVisibility(View.GONE);
        }

        binding.planExpiryText.setVisibility(View.VISIBLE);
        String expiresAt = plan.expiresAtIso();
        if (expiresAt == null) {
            binding.planExpiryText.setText(R.string.profile_plan_no_expiry);
        } else {
            binding.planExpiryText.setText(getString(R.string.profile_plan_expiry, formatDate(expiresAt)));
        }

        // The device allowance is deliberately not repeated here: the devices
        // tab shows "2 из 5" where devices are actually added or revoked.
    }

    /**
     * Tariff names live in the database in one language, so an English UI
     * rendered the trial as "Пробный · free". Translate the ids this build
     * knows; anything else keeps whatever the server called it, which is still
     * better than an internal id.
     */
    private String localizedPlanName(PlanSummary plan) {
        String id = plan.tariffId() == null ? "" : plan.tariffId().toLowerCase(Locale.ROOT);
        switch (id) {
            case "trial": return getString(R.string.tariff_trial);
            case "basic": return getString(R.string.tariff_basic);
            case "pro": return getString(R.string.tariff_pro);
            default: return plan.planName();
        }
    }

    private static String formatBytes(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb >= 1) {
            return String.format(Locale.getDefault(), "%.2f GB", gb);
        }
        double mb = bytes / (1024.0 * 1024);
        return String.format(Locale.getDefault(), "%.1f MB", mb);
    }

    private String formatDate(String iso) {
        try {
            return DateFormat.getDateInstance(DateFormat.MEDIUM)
                    .format(Date.from(Instant.parse(iso)));
        } catch (Exception e) {
            return iso;
        }
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

    /**
     * The one way into the web dashboard's billing from here, landing on the
     * plans themselves (DashboardView scrolls to #tariffs) rather than the
     * top of the page. There used to be a second button, "Пополнить баланс",
     * which opened "/" — the same dashboard, one anchor higher. Two buttons
     * for one destination read as two different things you could do.
     */
    private void openPlansPage() {
        WebHandoffLauncher.launch(requireContext(), apiClient, binding.getRoot(), "/#tariffs");
    }

    private void logout() {
        requireContext().startService(
                new Intent(requireContext(), XrayVpnService.class).setAction(XrayVpnService.ACTION_DISCONNECT));
        // apiClient.logout() now does a best-effort network call to revoke this
        // device before clearing the local session, so it can't run on the main
        // thread. It never throws (the revoke failure is swallowed internally),
        // but goToLogin() runs from both callbacks regardless, to be safe.
        Async.run(
                this,
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
