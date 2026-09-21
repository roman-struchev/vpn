package com.vpn.android.ui;

import android.os.Build;
import android.os.Bundle;

import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.databinding.ActivityMainBinding;
import com.vpn.android.ui.connect.ConnectFragment;
import com.vpn.android.ui.devices.DevicesFragment;
import com.vpn.android.ui.profile.ProfileFragment;
import com.vpn.android.update.AppUpdateManager;
import com.vpn.android.util.Async;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        apiClient = new ApiClient(new TokenStore(this));
        requestNotificationPermissionIfNeeded();

        if (savedInstanceState == null) {
            showFragment(new ConnectFragment());
            new AppUpdateManager(this).checkAndPrompt(this);
        }

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_connect) {
                showFragment(new ConnectFragment());
                return true;
            } else if (id == R.id.nav_devices) {
                showFragment(new DevicesFragment());
                return true;
            } else if (id == R.id.nav_profile) {
                showFragment(new ProfileFragment());
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkProfile();
    }

    public void setGuestMode(boolean isGuest) {
        if (binding != null) {
            binding.bottomNav.setVisibility(isGuest ? View.GONE : View.VISIBLE);
        }
    }

    private void checkProfile() {
        Async.run(
                () -> apiClient.getProfile(),
                profile -> {
                    boolean isGuest = profile != null && profile.isGuest;
                    setGuestMode(isGuest);
                    Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragmentContainer);
                    if (current instanceof ConnectFragment) {
                        ((ConnectFragment) current).setGuest(isGuest);
                    } else if (isGuest) {
                        showFragment(ConnectFragment.newInstance(true));
                    }
                },
                error -> { /* keep current state */ });
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
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
