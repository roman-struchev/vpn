package com.vpn.android.ui.login;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.vpn.android.R;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.api.model.AuthResponse;
import com.vpn.android.databinding.ActivityLoginBinding;
import com.vpn.android.ui.MainActivity;
import com.vpn.android.util.Async;

public class LoginActivity extends AppCompatActivity {

    /**
     * When set, the login/register form is shown immediately instead of
     * silently attempting the no-signup device trial login first. Used by
     * ProfileFragment's "sign in with an existing account" action so a user
     * who explicitly wants to authenticate isn't looped back into the
     * device auto-login.
     */
    public static final String EXTRA_FORCE_FORM = "force_form";

    private ActivityLoginBinding binding;
    private ApiClient apiClient;
    private TokenStore tokenStore;
    private boolean registerMode = false;

    /** Explicit form-showing mode, for a user who wants to sign in/register instead of using the auto-created trial account. */
    public static Intent createShowFormIntent(Context context) {
        Intent intent = new Intent(context, LoginActivity.class);
        intent.putExtra(EXTRA_FORCE_FORM, true);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        tokenStore = new TokenStore(this);
        apiClient = new ApiClient(tokenStore);

        boolean forceForm = getIntent().getBooleanExtra(EXTRA_FORCE_FORM, false);

        if (tokenStore.isLoggedIn() && !forceForm) {
            goToMain();
            return;
        }

        binding.submitButton.setOnClickListener(v -> submit());
        binding.toggleModeButton.setOnClickListener(v -> toggleMode());
        applyMode();

        if (forceForm) {
            binding.formContainer.setVisibility(View.VISIBLE);
        } else {
            // Fresh install (or a device-login that never completed): rather
            // than forcing registration/login, silently log this install
            // into its own auto-created, trial-tariff device account. The
            // form stays reachable via ProfileFragment's "sign in with an
            // existing account" for anyone who wants to keep their account
            // across reinstalls/devices.
            binding.formContainer.setVisibility(View.GONE);
            attemptDeviceLogin();
        }
    }

    private void attemptDeviceLogin() {
        setLoading(true);
        String deviceUuid = tokenStore.getOrCreateDeviceUuid();
        Async.run(
                () -> apiClient.deviceAuth(deviceUuid, null),
                (AuthResponse resp) -> goToMain(),
                error -> {
                    // No network / server unreachable — fall back to the
                    // manual login/register form so the app isn't unusable.
                    setLoading(false);
                    binding.formContainer.setVisibility(View.VISIBLE);
                });
    }

    private void toggleMode() {
        registerMode = !registerMode;
        applyMode();
    }

    private void applyMode() {
        binding.titleText.setText(registerMode ? R.string.register_action : R.string.login_title);
        binding.submitButton.setText(registerMode ? R.string.register_action : R.string.login_action);
        binding.toggleModeButton.setText(registerMode ? R.string.login_toggle : R.string.register_toggle);
    }

    private void submit() {
        String email = text(binding.emailInput);
        String password = text(binding.passwordInput);
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            showError(getString(R.string.field_required));
            return;
        }

        setLoading(true);
        Async.run(
                () -> registerMode ? apiClient.register(email, password, null) : apiClient.login(email, password),
                (AuthResponse resp) -> {
                    setLoading(false);
                    goToMain();
                },
                error -> {
                    setLoading(false);
                    showError(error.getMessage() != null ? error.getMessage() : getString(R.string.login_error_generic));
                });
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void setLoading(boolean loading) {
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.submitButton.setEnabled(!loading);
        binding.errorText.setVisibility(View.GONE);
    }

    private void showError(String message) {
        binding.errorText.setText(message);
        binding.errorText.setVisibility(View.VISIBLE);
    }

    private String text(com.google.android.material.textfield.TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }
}
