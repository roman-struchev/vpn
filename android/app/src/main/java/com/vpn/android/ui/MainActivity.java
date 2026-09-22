package com.vpn.android.ui;

import android.os.Build;
import android.os.Bundle;

import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.databinding.ActivityMainBinding;
import com.vpn.android.ui.connect.ConnectFragment;
import com.vpn.android.ui.login.LoginActivity;
import com.vpn.android.ui.profile.ProfileFragment;
import com.vpn.android.update.AppUpdateManager;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_CONNECT = "connect";
    private static final String TAG_PROFILE = "profile";

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        requestNotificationPermissionIfNeeded();

        if (savedInstanceState == null) {
            showTab(TAG_CONNECT);
            new AppUpdateManager(this).checkAndPrompt(this);
        }

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_connect) {
                showTab(TAG_CONNECT);
                return true;
            } else if (id == R.id.nav_profile) {
                showTab(TAG_PROFILE);
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // A session that ran out and could not be renewed silently (see
        // ApiClient#execute) — until now every screen just stopped loading.
        ApiClient.setSessionExpiredListener(() -> runOnUiThread(() -> {
            if (isFinishing()) return;
            startActivity(LoginActivity.createSessionExpiredIntent(this));
            finish();
        }));
    }

    @Override
    protected void onStop() {
        ApiClient.setSessionExpiredListener(null);
        super.onStop();
    }

    /**
     * Guest (device-trial) accounts get a single screen: no bottom nav, and
     * the connect tab in front. Reported by ConnectFragment from the profile
     * it loads anyway — this activity used to fetch the same profile a
     * second time on every resume just to decide this.
     */
    public void setGuestMode(boolean isGuest) {
        if (binding == null) return;
        binding.bottomNav.setVisibility(isGuest ? View.GONE : View.VISIBLE);
        if (isGuest) {
            showTab(TAG_CONNECT);
            binding.bottomNav.getMenu().findItem(R.id.nav_connect).setChecked(true);
        }
    }

    /**
     * Tabs are created once and then shown/hidden. Replacing them on every
     * switch rebuilt the screen and re-ran its profile, region and ping
     * requests each time the user tapped between two tabs.
     */
    private void showTab(String tag) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment target = fm.findFragmentByTag(tag);
        androidx.fragment.app.FragmentTransaction tx = fm.beginTransaction();
        for (Fragment f : fm.getFragments()) {
            if (f != target && !f.isHidden()) tx.hide(f);
        }
        if (target == null) {
            target = TAG_PROFILE.equals(tag) ? new ProfileFragment() : new ConnectFragment();
            tx.add(R.id.fragmentContainer, target, tag);
        } else if (target.isHidden()) {
            tx.show(target);
        }
        tx.commit();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        // "IfNeeded" was doing no such check: this runs from onCreate, so
        // every rotation — and every return to an activity the system had
        // killed — asked again, including of someone who had already said
        // yes.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
    }
}
