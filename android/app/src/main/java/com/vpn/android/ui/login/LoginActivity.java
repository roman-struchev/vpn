package com.vpn.android.ui.login;

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

    private ActivityLoginBinding binding;
    private ApiClient apiClient;
    private TokenStore tokenStore;
    private boolean registerMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        tokenStore = new TokenStore(this);
        apiClient = new ApiClient(tokenStore);

        if (tokenStore.isLoggedIn()) {
            goToMain();
            return;
        }

        binding.submitButton.setOnClickListener(v -> submit());
        binding.toggleModeButton.setOnClickListener(v -> toggleMode());
        applyMode();
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
