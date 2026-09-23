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
import android.text.TextWatcher;
import android.text.Editable;

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
    /** Opened because the session ran out (see MainActivity): say so above the form. */
    public static final String EXTRA_SESSION_EXPIRED = "session_expired";

    /** The sign-in form for a user whose session could not be renewed, as a fresh task. */
    public static Intent createSessionExpiredIntent(Context context) {
        Intent intent = createShowFormIntent(context, false);
        intent.putExtra(EXTRA_SESSION_EXPIRED, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return intent;
    }

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
        binding.codeSignInButton.setOnClickListener(v -> askForLoginCode());
        binding.forgotPasswordButton.setOnClickListener(v -> askForResetEmail());
        binding.retryButton.setOnClickListener(v -> attemptDeviceLogin());
        applyMode();
        clearErrorsOnInput();

        if (forceForm) {
            binding.formContainer.setVisibility(View.VISIBLE);
            if (getIntent().getBooleanExtra(EXTRA_SESSION_EXPIRED, false)) {
                showError(getString(R.string.session_expired_message));
            }
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
        // Mark the field that is actually empty. The shared error label at the
        // bottom of the screen sits under the Google button, several controls
        // away from either input — "This field is required" there left the
        // user guessing which field it meant.
        binding.emailInputLayout.setError(TextUtils.isEmpty(email) ? getString(R.string.field_required) : null);
        binding.passwordInputLayout.setError(TextUtils.isEmpty(password) ? getString(R.string.field_required) : null);
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
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
                    showError(getString(errorMessage(error)));
                });
    }

    /**
     * A one-time code from the Telegram bot (/login) or the web dashboard's
     * Account section: the only way into the app for an account made in
     * Telegram, which has no password.
     */
    private void askForLoginCode() {
        android.widget.EditText input = dialogInput(getString(R.string.login_code_hint), android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.login_code_title)
                .setMessage(R.string.login_code_message)
                .setView(padded(input))
                .setPositiveButton(R.string.login_action, (d, w) -> {
                    String code = input.getText().toString().trim();
                    if (code.isEmpty()) return;
                    setLoading(true);
                    Async.run(
                            () -> apiClient.loginWithCode(code),
                            (AuthResponse resp) -> goToMain(),
                            error -> {
                                setLoading(false);
                                showError(getString(errorMessage(error)));
                            });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Forgotten password, step 1: where to send the code (the account's Telegram and email). */
    private void askForResetEmail() {
        android.widget.EditText input = dialogInput(getString(R.string.login_email_hint),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setText(text(binding.emailInput));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_intro)
                .setView(padded(input))
                .setPositiveButton(R.string.reset_send_code, (d, w) -> {
                    String email = input.getText().toString().trim();
                    if (email.isEmpty()) return;
                    setLoading(true);
                    Async.run(
                            () -> {
                                apiClient.requestPasswordReset(email);
                                return email;
                            },
                            sentTo -> {
                                setLoading(false);
                                askForResetCode(sentTo);
                            },
                            error -> {
                                setLoading(false);
                                showError(getString(errorMessage(error)));
                            });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Step 2: the code and a new password; signs straight in. */
    private void askForResetCode(String email) {
        android.widget.EditText code = dialogInput(getString(R.string.reset_code_hint), android.text.InputType.TYPE_CLASS_NUMBER);
        android.widget.EditText password = dialogInput(getString(R.string.reset_new_password_hint),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        android.widget.LinearLayout fields = new android.widget.LinearLayout(this);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        fields.addView(code);
        fields.addView(password);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.reset_title)
                .setMessage(getString(R.string.reset_code_sent, email))
                .setView(padded(fields))
                .setPositiveButton(R.string.reset_save, (d, w) -> {
                    setLoading(true);
                    Async.run(
                            () -> apiClient.confirmPasswordReset(email, code.getText().toString().trim(),
                                    password.getText().toString()),
                            (AuthResponse resp) -> goToMain(),
                            error -> {
                                setLoading(false);
                                showError(getString(errorMessage(error)));
                            });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private android.widget.EditText dialogInput(String hint, int inputType) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setSingleLine(true);
        return input;
    }

    private View padded(View content) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        frame.setPadding(pad, 0, pad, 0);
        frame.addView(content);
        return frame;
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
            Toast.makeText(this, R.string.google_signin_not_configured, Toast.LENGTH_LONG).show();
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
                    showError(getString(errorMessage(error)));
                });
    }

    /** A message in the user's language rather than the server's English, or an IOException's. */
    private static int errorMessage(Exception error) {
        switch (LoginError.classify(error)) {
            case INVALID_CREDENTIALS: return R.string.login_error_invalid_credentials;
            case EMAIL_TAKEN: return R.string.login_error_email_taken;
            case PASSWORD_TOO_SHORT: return R.string.login_error_password_short;
            case ACCOUNT_BLOCKED: return R.string.login_error_blocked;
            case CODE_INVALID: return R.string.login_error_code_invalid;
            case NETWORK: return R.string.server_unavailable_message;
            default: return R.string.login_error_generic;
        }
    }

    private void goToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    /**
     * Clears a field's error as soon as it is typed into. Without this the
     * complaint stayed on screen while the user was busy fixing it, so the
     * form looked broken even once it was valid — and the message survived a
     * successful correction all the way to the next submit.
     */
    private void clearErrorsOnInput() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.emailInputLayout.setError(null);
                binding.passwordInputLayout.setError(null);
                binding.errorText.setVisibility(View.GONE);
            }

            @Override public void afterTextChanged(Editable s) {}
        };
        binding.emailInput.addTextChangedListener(watcher);
        binding.passwordInput.addTextChangedListener(watcher);
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
