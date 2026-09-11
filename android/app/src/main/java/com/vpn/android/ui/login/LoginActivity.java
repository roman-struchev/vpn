package com.vpn.android.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
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
        binding.googleSignInButton.setOnClickListener(v -> signInWithGoogle());
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

    /**
     * Google Sign-In via the Credential Manager API (androidx.credentials) — the
     * current recommended replacement for the deprecated GoogleSignInClient. The
     * "server client ID" passed to GetGoogleIdOption must be a Web-application-type
     * OAuth Client ID from Google Cloud Console, matching the audience the backend's
     * vpn.google.client-id property expects when it verifies the token; it is NOT
     * the Android-type client ID. See R.string.google_web_client_id for the
     * placeholder that needs a real value before this can work end-to-end.
     */
    private void signInWithGoogle() {
        GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(getString(R.string.google_web_client_id))
                .build();

        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build();

        CredentialManager credentialManager = CredentialManager.create(this);
        setLoading(true);
        credentialManager.getCredentialAsync(
                this,
                request,
                new CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleGoogleCredential(result);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        // Covers "no Google account on this device", user cancellation,
                        // and any other Credential Manager failure.
                        setLoading(false);
                        showError(getString(R.string.google_signin_error));
                    }
                });
    }

    private void handleGoogleCredential(GetCredentialResponse result) {
        Credential credential = result.getCredential();
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            setLoading(false);
            showError(getString(R.string.google_signin_error));
            return;
        }

        String idToken;
        try {
            // createFrom is declared to throw GoogleIdTokenParsingException in Kotlin,
            // but that's not reflected in the compiled method signature javac sees, so
            // a checked catch clause for it doesn't compile — catch broadly instead.
            GoogleIdTokenCredential googleIdTokenCredential =
                    GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
            idToken = googleIdTokenCredential.getIdToken();
        } catch (Exception e) {
            setLoading(false);
            showError(getString(R.string.google_signin_error));
            return;
        }

        Async.run(
                () -> apiClient.googleAuth(idToken, null),
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
