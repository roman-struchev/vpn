package com.vpn.android.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.api.model.RegionInfo;
import com.vpn.android.databinding.ActivitySettingsBinding;
import com.vpn.android.util.Async;
import com.vpn.android.util.GeoLocale;
import com.vpn.android.vpn.VpnStarter;

import java.util.List;

/**
 * Connection settings — the RU route and auto-connect-on-boot.
 *
 * These used to be a card on the connect screen, where they were the two
 * largest blocks on it and between them carried four lines of explanation,
 * for controls a user sets once per install. The connect screen now shows a
 * one-line summary of the RU route that opens this, so the feature stays
 * discoverable (it is the product's point for Russian users) without
 * occupying the screen you look at every time you connect.
 */
public class SettingsActivity extends AppCompatActivity {

    public static Intent intent(Context context) {
        return new Intent(context, SettingsActivity.class);
    }

    private ActivitySettingsBinding binding;
    private TokenStore tokenStore;
    private ApiClient apiClient;

    /** Only used to decide whether the RU-only mode has a Russian node to actually use — see updateWarning. */
    private List<RegionInfo> availableRegions = List.of();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        tokenStore = new TokenStore(this);
        apiClient = new ApiClient(tokenStore);

        binding.autoBootSwitch.setChecked(tokenStore.isAutoConnectOnBoot());
        binding.autoBootSwitch.setOnCheckedChangeListener(
                (button, isChecked) -> tokenStore.setAutoConnectOnBoot(isChecked));

        setUpRussianRoutingControl();
        loadRegions();
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
        // Not revealed from this callback — see ConnectFragment for why: a
        // block appearing seconds after the screen settled is worse than one
        // that waits for the next launch, and the answer is cached.
        GeoLocale.lookupOriginalIpIsRussiaAsync(tokenStore, isRussia -> { });
    }

    private void showRussianRoutingControl() {
        if (binding == null) return;
        binding.russianRoutingContainer.setVisibility(View.VISIBLE);
        renderMode(tokenStore.getRussianRoutingMode());

        binding.russianRoutingToggleGroup.check(buttonIdFor(tokenStore.getRussianRoutingMode()));
        binding.russianRoutingToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String newMode = checkedId == binding.russianRoutingBypassButton.getId()
                    ? TokenStore.RUSSIAN_ROUTING_BYPASS
                    : checkedId == binding.russianRoutingOnlyRuButton.getId()
                    ? TokenStore.RUSSIAN_ROUTING_ONLY_RU
                    : TokenStore.RUSSIAN_ROUTING_OFF;
            tokenStore.setRussianRoutingMode(newMode);
            // Deliberately does NOT re-check the toggle group: this runs from
            // inside that group's own checked-listener, and calling check()
            // there re-enters it. It used to, and only happened to settle.
            renderMode(newMode);
            VpnStarter.reconnectIfActive(this);
        });
    }

    /** The description and the warning for a mode — everything except the toggle itself. */
    private void renderMode(String mode) {
        int descRes = TokenStore.RUSSIAN_ROUTING_BYPASS.equals(mode)
                ? R.string.russian_routing_bypass_desc
                : TokenStore.RUSSIAN_ROUTING_ONLY_RU.equals(mode)
                ? R.string.russian_routing_only_ru_desc
                : R.string.russian_routing_off_desc;
        binding.russianRoutingDescText.setText(descRes);
        // Nothing to explain about the default: "every site goes through the
        // VPN" is what a VPN does. The line is for the two modes that change
        // that, so it only appears once one of them is picked.
        binding.russianRoutingDescText.setVisibility(
                TokenStore.RUSSIAN_ROUTING_OFF.equals(mode) ? View.GONE : View.VISIBLE);
        updateWarning();
    }

    private int buttonIdFor(String mode) {
        return TokenStore.RUSSIAN_ROUTING_BYPASS.equals(mode)
                ? binding.russianRoutingBypassButton.getId()
                : TokenStore.RUSSIAN_ROUTING_ONLY_RU.equals(mode)
                ? binding.russianRoutingOnlyRuButton.getId()
                : binding.russianRoutingOffButton.getId();
    }

    /** Only RU-only mode needs an actual Russia-located node to do anything useful. */
    private void updateWarning() {
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

    private void loadRegions() {
        Async.run(
                () -> apiClient.getRegions(),
                regions -> {
                    if (binding == null) return;
                    availableRegions = regions;
                    updateWarning();
                },
                error -> { /* the warning stays hidden — never worth an error of its own */ });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
