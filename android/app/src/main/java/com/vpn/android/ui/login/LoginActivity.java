package com.vpn.android.ui.login;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

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

    /**
     * When set, the login/register form is shown immediately instead of
     * silently attempting the no-signup device trial login first. Used by
     * ProfileFragment's "sign in with an existing account" action so a user
     * who explicitly wants to authenticate isn't looped back into the
     * device auto-login.
     */
    public static final String EXTRA_FORCE_FORM = "force_form";
    public static final String EXTRA_IS_GUEST_SESSION = "is_guest_session";

    private ActivityLoginBinding binding;
    private ApiClient apiClient;
    private TokenStore tokenStore;
    private boolean registerMode = false;

    /** Explicit form-showing mode, for a user who wants to sign in/register instead of using the auto-created trial account. */
    public static Intent createShowFormIntent(Context context) {
        return createShowFormIntent(context, false);
    }

    public static Intent createShowFormIntent(Context context, boolean isGuestSession) {
        Intent intent = new Intent(context, LoginActivity.class);
        intent.putExtra(EXTRA_FORCE_FORM, true);
        intent.putExtra(EXTRA_IS_GUEST_SESSION, isGuestSession);
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
        binding.googleSignInButton.setOnClickListener(v -> signInWithGoogle());
        binding.retryButton.setOnClickListener(v -> attemptDeviceLogin());
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
        binding.retryButton.setVisibility(View.GONE);
        String deviceUuid = tokenStore.getOrCreateDeviceUuid();
        Async.run(
                () -> apiClient.deviceAuth(deviceUuid, null),
                (AuthResponse resp) -> goToMain(),
                error -> {
                    setLoading(false);
                    if (error instanceof java.io.IOException) {
                        // Plain network failure (DNS/connect/timeout — server
                        // unreachable), not a real "invalid session" response
                        // from the server (that would be an ApiException).
                        // Previously this fell through to the login/register
                        // form exactly like a real auth failure, which reads
                        // as "you need to sign in" when the actual problem is
                        // connectivity and retrying is what would actually
                        // help — same class of bug fixed on the desktop client.
                        showError(getString(R.string.server_unavailable_message));
                        binding.retryButton.setVisibility(View.VISIBLE);
                        return;
                    }
                    // A real (if unexpected) HTTP error response — fall back to
                    // the manual login/register form so the app isn't unusable.
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

        boolean isGuestSession = getIntent().getBooleanExtra(EXTRA_IS_GUEST_SESSION, false);
        setLoading(true);
        Async.run(
                () -> {
                    if (registerMode) {
                        return isGuestSession
                                ? apiClient.upgradeGuest(email, password)
                                : apiClient.register(email, password, null);
                    } else {
                        return apiClient.login(email, password);
                    }
                },
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
        String clientId = getString(R.string.google_web_client_id);
        if (clientId == null || clientId.isBlank() || "REPLACE_WITH_GOOGLE_WEB_CLIENT_ID".equals(clientId)) {
            Toast.makeText(this, "Google Sign-In is not configured", Toast.LENGTH_LONG).show();
            return;
        }

        GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(clientId)
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
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
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
