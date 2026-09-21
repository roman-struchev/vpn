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
import androidx.recyclerview.widget.LinearLayoutManager;

import com.vpn.android.BuildConfig;
import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.databinding.FragmentProfileBinding;
import com.vpn.android.p2p.P2pRelaySettingsActivity;
import com.vpn.android.ui.devices.DeviceAdapter;
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
    /** Whether anything has come back yet — the loading bar is for the first answer only. */
    private boolean loadedOnce;
    private DeviceAdapter deviceAdapter;
    private Integer knownDeviceCount;
    /** The plan's device allowance, from the same profile load that fills the plan card. */
    private Integer knownMaxDevices;

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
        binding.shareReferralButton.setOnClickListener(v -> shareReferralLink());
        binding.changePlanButton.setOnClickListener(v -> openPlansPage());
        binding.settingsButton.setOnClickListener(v ->
                startActivity(com.vpn.android.ui.settings.SettingsActivity.intent(requireContext())));
        binding.p2pRelayButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), P2pRelaySettingsActivity.class)));

        deviceAdapter = new DeviceAdapter(this::confirmRevoke);
        binding.devicesList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.devicesList.setAdapter(deviceAdapter);
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
        loadDevices();
        renderP2pRow();
    }

    /**
     * The P2P row states what the mode is doing, not just that it exists —
     * the settings screen closes as soon as it is applied, so this row is
     * where the user sees that anything happened. Read from the local store
     * rather than the server: the service keeps it, and it is what the
     * settings screen itself shows.
     */
    private void renderP2pRow() {
        if (binding == null) return;
        String mode = tokenStore.getP2pRelayMode();
        String state;
        if (TokenStore.P2P_RELAY_ALWAYS.equals(mode)) {
            state = getString(R.string.p2p_state_always);
        } else if (TokenStore.P2P_RELAY_TIMED.equals(mode)
                && tokenStore.getP2pRelayExpiresAt() > System.currentTimeMillis()) {
            state = getString(R.string.p2p_state_until,
                    android.text.format.DateFormat.getTimeFormat(requireContext())
                            .format(new Date(tokenStore.getP2pRelayExpiresAt())));
        } else {
            // Includes a TIMED window that has already elapsed: the service
            // turns itself off then, so saying "on until 14:00" at 15:00
            // would be a stale promise.
            state = getString(R.string.p2p_state_off);
        }
        binding.p2pRelayButton.setText(getString(R.string.profile_row_p2p_state, state));
    }

    /**
     * The device list, which used to be a bottom-nav tab with an "add device"
     * button on it. There is nothing to add by hand: a client registers
     * itself the first time it connects (XrayVpnService#registerOrTouchDevice,
     * and the same on desktop), so what is left is seeing what is registered
     * and removing one.
     */
    private void loadDevices() {
        Async.run(
                this,
                () -> apiClient.getDevices(),
                devices -> {
                    if (binding == null) return;
                    knownDeviceCount = devices.size();
                    deviceAdapter.submitList(devices);
                    binding.devicesEmptyText.setText(R.string.devices_empty);
                    binding.devicesEmptyText.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
                    renderDeviceCount();
                },
                error -> {
                    if (binding == null) return;
                    // Said in place of the list, not only in a toast that is
                    // gone in seconds: a failed load and an empty account
                    // looked identical before, and the failure was the one
                    // that needed explaining.
                    binding.devicesEmptyText.setText(getString(R.string.devices_load_failed, messageOf(error)));
                    binding.devicesEmptyText.setVisibility(View.VISIBLE);
                });
    }

    private void renderDeviceCount() {
        if (binding == null || knownDeviceCount == null) return;
        binding.devicesUsageText.setText(knownMaxDevices != null
                ? getString(R.string.profile_devices_count, knownDeviceCount, knownMaxDevices)
                : getString(R.string.profile_devices_count_unknown, knownDeviceCount));
    }

    private void confirmRevoke(com.vpn.android.api.model.DeviceDto device) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.revoke_device_confirm_title)
                .setMessage(getString(R.string.confirm_revoke_device, device.deviceName))
                .setPositiveButton(R.string.revoke_device_action, (dialog, which) -> revokeDevice(device))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void revokeDevice(com.vpn.android.api.model.DeviceDto device) {
        Async.run(
                this,
                () -> {
                    apiClient.deleteDevice(device.id);
                    return null;
                },
                ignored -> loadDevices(),
                error -> Toast.makeText(requireContext(), messageOf(error), Toast.LENGTH_LONG).show());
    }

    /** Never null: an exception with no message would otherwise lose the error entirely. */
    private String messageOf(Throwable error) {
        String message = error != null ? error.getMessage() : null;
        return message != null && !message.isBlank()
                ? message
                : getString(R.string.devices_load_failed_unknown_reason);
    }

    private void loadProfile() {
        if (!loadedOnce) {
            binding.profileLoading.setVisibility(View.VISIBLE);
        }
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
                    loadedOnce = true;
                    binding.profileLoading.setVisibility(View.INVISIBLE);
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
                error -> {
                    // Keep whatever is on screen, but stop claiming to be
                    // loading — an indicator that never goes away is worse
                    // than none.
                    loadedOnce = true;
                    if (binding != null) binding.profileLoading.setVisibility(View.INVISIBLE);
                });

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
        // The allowance comes with the plan, and the device count next to the
        // list is the only place it is shown now — the devices tab used to
        // fetch the profile and the tariff catalogue a second time for it.
        knownMaxDevices = plan.maxDevices();
        renderDeviceCount();
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

    /**
     * The only way out of an account from here. There used to be a second
     * button, "Войти в другой аккаунт", which opened the login form without
     * clearing anything — and since logging out lands on that same form, it
     * was a shortcut to the same place that happened to leave the old
     * session behind.
     */
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
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
