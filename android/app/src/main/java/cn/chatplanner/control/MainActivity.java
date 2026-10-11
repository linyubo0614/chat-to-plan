package cn.chatplanner.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CredentialStore credentials;
    private EditText server, token, key;
    private Switch enabled;
    private Spinner model;
    private TextView status, keyStatus;
    private Button connect, save, clear, test;
    private String connectedBase = "", connectedToken = "";
    private boolean busy = false;
    private volatile boolean destroyed = false;
    private final int ink = Color.rgb(27, 47, 43), teal = Color.rgb(8, 127, 116);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        credentials = new CredentialStore(this);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(243, 247, 245));
        LinearLayout root = column(); root.setPadding(dp(24), dp(28), dp(24), dp(32)); scroll.addView(root);
        // Respect system bars on devices enforcing edge-to-edge for target SDK 35.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView heading = label(R.string.app_name, 30); heading.setTypeface(null, Typeface.BOLD); root.addView(heading);
        root.addView(label(R.string.subtitle, 15));
        status = label(R.string.disconnected, 14); status.setTextColor(teal); root.addView(status);

        LinearLayout connection = card(root, R.string.connection);
        server = input(connection, R.string.server_hint, false); server.setText(credentials.server());
        token = input(connection, R.string.token_hint, true);
        connect = button(connection, R.string.connect); connect.setOnClickListener(v -> connect());

        LinearLayout control = card(root, R.string.processing);
        enabled = new Switch(this); enabled.setText(R.string.switch_label); enabled.setTextSize(17); enabled.setTextColor(ink); enabled.setChecked(true); control.addView(enabled);
        control.addView(label(R.string.switch_help, 13));
        key = input(control, R.string.key_hint, true);
        keyStatus = label(R.string.key_missing, 13); control.addView(keyStatus);
        control.addView(label(R.string.model_label, 13));
        model = new Spinner(this); model.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"deepseek-chat", "deepseek-reasoner"})); control.addView(model);
        save = button(control, R.string.save); save.setOnClickListener(v -> save(false));
        clear = button(control, R.string.clear_key);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(R.string.clear_title).setMessage(R.string.clear_message)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm, (d, w) -> save(true)).show());

        LinearLayout integrations = card(root, R.string.integrations);
        integrations.addView(label(R.string.integration_default, 14));
        test = button(integrations, R.string.test_message); test.setOnClickListener(v -> testMessage());
        root.addView(label(R.string.security, 12));
        setContentView(scroll); setBusy(false);
        if (!credentials.server().isEmpty()) connect();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView label(int text, int size) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(ink); v.setPadding(0, dp(5), 0, dp(9)); return v; }
    private LinearLayout card(LinearLayout root, int title) {
        LinearLayout view = column(); view.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(18)); view.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(18), 0, 0); root.addView(view, params);
        TextView heading = label(title, 18); heading.setTypeface(null, Typeface.BOLD); view.addView(heading); return view;
    }
    private EditText input(LinearLayout parent, int hint, boolean secret) {
        EditText view = new EditText(this); view.setHint(hint); view.setTextSize(14); view.setSingleLine(true);
        view.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); parent.addView(view, new LinearLayout.LayoutParams(-1, dp(58))); return view;
    }
    private Button button(LinearLayout parent, int text) { Button b = new Button(this); b.setText(text); b.setTextColor(teal); b.setAllCaps(false); parent.addView(b, new LinearLayout.LayoutParams(-1, dp(54))); return b; }
    private void post(Runnable runnable) { ui.post(() -> { if (!destroyed) runnable.run(); }); }
    private void setBusy(boolean value) {
        busy = value; boolean connected = !connectedBase.isEmpty();
        connect.setEnabled(!busy); save.setEnabled(!busy && connected); clear.setEnabled(!busy && connected);
        test.setEnabled(!busy && connected); server.setEnabled(!busy); token.setEnabled(!busy);
        key.setEnabled(!busy && connected); enabled.setEnabled(!busy && connected); model.setEnabled(!busy && connected);
    }
    private void failed() { post(() -> { status.setText(R.string.failed); setBusy(false); }); }

    private void connect() {
        final String base;
        try { base = ApiClient.validateBase(server.getText().toString()); }
        catch (Exception ex) { status.setText(R.string.invalid_url); return; }
        final String typedToken = token.getText().toString().trim();
        connectedBase = ""; connectedToken = ""; setBusy(true); status.setText(R.string.connecting);
        io.execute(() -> {
            try {
                // A saved bearer token must never be sent to a newly entered server.
                String secret = typedToken.isEmpty() && base.equals(credentials.server()) ? credentials.token() : typedToken;
                if (secret.length() < 24) { post(() -> { status.setText(R.string.missing_token); setBusy(false); }); return; }
                JSONObject settings = ApiClient.request(base, secret, "GET", "/v1/settings", null);
                JSONObject queues = ApiClient.request(base, secret, "GET", "/v1/status", null);
                credentials.save(base, secret);
                post(() -> { connectedBase = base; connectedToken = secret; token.setText(""); apply(settings, queues); setBusy(false); });
            } catch (Exception ex) { failed(); }
        });
    }

    private void apply(JSONObject settings, JSONObject state) {
        enabled.setChecked(settings.optBoolean("enabled", true));
        model.setSelection("deepseek-reasoner".equals(settings.optString("model")) ? 1 : 0);
        keyStatus.setText(settings.optBoolean("deepseek_key_configured") ? R.string.key_present : R.string.key_missing);
        int pending = 0;
        JSONObject queues = state.optJSONObject("queues"); JSONObject messages = queues == null ? null : queues.optJSONObject("messages");
        if (messages != null) pending = messages.optInt("pending") + messages.optInt("retry");
        status.setText(getString(R.string.connected_status, getString(enabled.isChecked() ? R.string.enabled : R.string.paused), pending));
    }

    private void save(boolean removeKey) {
        if (busy || connectedBase.isEmpty()) return;
        final String base = connectedBase, secret = connectedToken, providerKey = key.getText().toString().trim();
        final boolean active = enabled.isChecked(); final String selected = model.getSelectedItem().toString();
        setBusy(true); status.setText(R.string.saving);
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("enabled", active).put("model", selected);
                if (removeKey || !providerKey.isEmpty()) body.put("deepseek_api_key", removeKey ? "" : providerKey);
                JSONObject settings = ApiClient.request(base, secret, "PUT", "/v1/settings", body);
                post(() -> { key.setText(""); enabled.setChecked(settings.optBoolean("enabled")); keyStatus.setText(settings.optBoolean("deepseek_key_configured") ? R.string.key_present : R.string.key_missing); status.setText(R.string.saved); setBusy(false); });
            } catch (Exception ex) { failed(); }
        });
    }

    private void testMessage() {
        EditText text = new EditText(this); text.setHint(R.string.test_help); text.setMinLines(3); text.setMaxLines(8);
        new AlertDialog.Builder(this).setTitle(R.string.test_title).setView(text).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.send, (d, w) -> {
                final String message = text.getText().toString().trim(); if (message.isEmpty()) return;
                final String base = connectedBase, secret = connectedToken;
                setBusy(true);
                io.execute(() -> {
                    try {
                        JSONObject body = new JSONObject().put("message_id", UUID.randomUUID().toString()).put("text", message);
                        JSONObject result = ApiClient.request(base, secret, "POST", "/v1/messages", body);
                        String id = result.getString("id"); post(() -> status.setText(R.string.received));
                        poll(base, secret, id, 0);
                    } catch (Exception ex) { failed(); }
                });
            }).show();
    }

    private void poll(String base, String secret, String id, int attempt) {
        if (destroyed) return;
        try {
            JSONObject result = ApiClient.request(base, secret, "GET", "/v1/messages/" + id, null);
            if ("failed".equals(result.optString("status"))) { post(() -> { status.setText(R.string.analysis_failed); setBusy(false); }); return; }
            if ("parsed".equals(result.optString("status"))) {
                JSONArray tasks = result.optJSONArray("tasks"); StringBuilder summary = new StringBuilder();
                if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
                    JSONObject task = tasks.getJSONObject(i); JSONObject item = task.getJSONObject("payload");
                    summary.append(item.optString("title")).append(" → ").append(getString("hita".equals(task.optString("target")) ? R.string.hita : R.string.tomato))
                        .append("\n").append(task.optString("reason")).append("\n\n");
                }
                if (!result.isNull("question")) summary.append(result.optString("question"));
                String display = summary.toString(); post(() -> { status.setText(R.string.analysis_result); setBusy(false); new AlertDialog.Builder(this).setTitle(R.string.analysis_result).setMessage(display).setPositiveButton(android.R.string.ok, null).show(); });
                return;
            }
            if (attempt >= 30) { post(() -> { status.setText(R.string.still_pending); setBusy(false); }); return; }
            ui.postDelayed(() -> { if (!destroyed) io.execute(() -> poll(base, secret, id, attempt + 1)); }, 2000);
        } catch (Exception ex) { failed(); }
    }

    @Override protected void onDestroy() {
        destroyed = true; ui.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy();
    }
}
