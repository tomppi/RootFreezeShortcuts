package com.tomppi.freezeshortcuts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String PREFS = "settings";
    static final String PREF_AUTO_REFREEZE = "auto_refreeze";
    static final String PREF_GRACE_SECONDS = "grace_seconds";
    static final String PREF_MAX_WAIT_SECONDS = "max_wait_seconds";
    static final String PREF_INCLUDE_SYSTEM = "include_system";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private TextView rootStatus;
    private LinearLayout appList;
    private EditText graceSeconds;
    private EditText maxWaitSeconds;
    private CheckBox autoRefreeze;
    private CheckBox includeSystem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        loadSettingsIntoUi();
        checkRoot();
        refreshApps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveSettingsFromUi();
        io.shutdownNow();
    }

    private View buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Root Freeze Shortcuts");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView help = new TextView(this);
        help.setText("Create a proxy shortcut for an app. The shortcut unfreezes the target with root, launches it, then refreezes it after you leave it.");
        help.setTextSize(14);
        help.setPadding(0, dp(6), 0, dp(12));
        root.addView(help, matchWrap());

        rootStatus = new TextView(this);
        rootStatus.setText("Root: checking…");
        rootStatus.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(rootStatus, matchWrap());

        autoRefreeze = new CheckBox(this);
        autoRefreeze.setText("Refreeze automatically after the target is no longer foreground");
        autoRefreeze.setOnCheckedChangeListener((buttonView, isChecked) -> saveSettingsFromUi());
        root.addView(autoRefreeze, matchWrap());

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        TextView graceLabel = new TextView(this);
        graceLabel.setText("Leave grace seconds: ");
        row1.addView(graceLabel);
        graceSeconds = numericEdit("20");
        row1.addView(graceSeconds, new LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(row1, matchWrap());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView waitLabel = new TextView(this);
        waitLabel.setText("Launch timeout seconds: ");
        row2.addView(waitLabel);
        maxWaitSeconds = numericEdit("90");
        row2.addView(maxWaitSeconds, new LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(row2, matchWrap());

        includeSystem = new CheckBox(this);
        includeSystem.setText("Show system apps too — dangerous, leave off unless you know the package");
        includeSystem.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                saveSettingsFromUi();
                refreshApps();
            }
        });
        root.addView(includeSystem, matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, dp(10));
        Button checkRoot = new Button(this);
        checkRoot.setText("Check root");
        checkRoot.setOnClickListener(v -> checkRoot());
        buttons.addView(checkRoot, weightWrap());
        Button refresh = new Button(this);
        refresh.setText("Refresh apps");
        refresh.setOnClickListener(v -> refreshApps());
        buttons.addView(refresh, weightWrap());
        root.addView(buttons, matchWrap());

        TextView listTitle = new TextView(this);
        listTitle.setText("Launchable apps");
        listTitle.setTextSize(18);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(listTitle, matchWrap());

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        root.addView(appList, matchWrap());
        return scrollView;
    }

    private EditText numericEdit(String defaultText) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setText(defaultText);
        edit.setSelectAllOnFocus(true);
        edit.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) saveSettingsFromUi(); });
        return edit;
    }

    private void loadSettingsIntoUi() {
        autoRefreeze.setChecked(prefs.getBoolean(PREF_AUTO_REFREEZE, true));
        graceSeconds.setText(String.valueOf(prefs.getInt(PREF_GRACE_SECONDS, 20)));
        maxWaitSeconds.setText(String.valueOf(prefs.getInt(PREF_MAX_WAIT_SECONDS, 90)));
        includeSystem.setChecked(prefs.getBoolean(PREF_INCLUDE_SYSTEM, false));
    }

    private void saveSettingsFromUi() {
        prefs.edit()
                .putBoolean(PREF_AUTO_REFREEZE, autoRefreeze.isChecked())
                .putInt(PREF_GRACE_SECONDS, readInt(graceSeconds, 20, 1, 3600))
                .putInt(PREF_MAX_WAIT_SECONDS, readInt(maxWaitSeconds, 90, 10, 3600))
                .putBoolean(PREF_INCLUDE_SYSTEM, includeSystem.isChecked())
                .apply();
    }

    private int readInt(EditText editText, int def, int min, int max) {
        try {
            int v = Integer.parseInt(editText.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception ignored) {
            return def;
        }
    }

    private void checkRoot() {
        rootStatus.setText("Root: checking…");
        io.execute(() -> {
            RootShell.Result r = RootShell.hasRoot();
            boolean root = RootShell.outputLooksRoot(r);
            runOnUiThread(() -> {
                rootStatus.setText(root ? "Root: available" : "Root: not available / denied\n" + r.output.trim());
                rootStatus.setTextColor(root ? Color.rgb(0, 120, 0) : Color.rgb(180, 0, 0));
            });
        });
    }

    private void refreshApps() {
        saveSettingsFromUi();
        appList.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Loading apps…");
        appList.addView(loading, matchWrap());
        io.execute(() -> {
            boolean showSystem = prefs.getBoolean(PREF_INCLUDE_SYSTEM, false);
            List<AppUtils.AppEntry> apps = AppUtils.loadLaunchableApps(this, showSystem);
            runOnUiThread(() -> {
                appList.removeAllViews();
                if (apps.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText("No launchable apps found.");
                    appList.addView(empty, matchWrap());
                    return;
                }
                for (AppUtils.AppEntry app : apps) {
                    appList.addView(appRow(app), matchWrap());
                }
            });
        });
    }

    private View appRow(AppUtils.AppEntry app) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));
        box.setBackgroundColor(Color.argb(10, 0, 0, 0));

        TextView name = new TextView(this);
        name.setText(app.label);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextSize(16);
        box.addView(name, matchWrap());

        TextView pkg = new TextView(this);
        pkg.setText(app.packageName + "  •  " + AppUtils.humanState(app));
        pkg.setTextSize(12);
        box.addView(pkg, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(4), 0, 0);

        Button pin = new Button(this);
        pin.setText("Pin shortcut");
        pin.setOnClickListener(v -> pinShortcut(app.packageName, app.label));
        actions.addView(pin, weightWrap());

        Button launch = new Button(this);
        launch.setText("Launch");
        launch.setOnClickListener(v -> launchProxy(app.packageName, app.label));
        actions.addView(launch, weightWrap());
        box.addView(actions, matchWrap());

        LinearLayout rootActions = new LinearLayout(this);
        rootActions.setOrientation(LinearLayout.HORIZONTAL);
        Button freeze = new Button(this);
        freeze.setText("Freeze now");
        freeze.setOnClickListener(v -> rootToggle(app.packageName, false));
        rootActions.addView(freeze, weightWrap());
        Button unfreeze = new Button(this);
        unfreeze.setText("Unfreeze now");
        unfreeze.setOnClickListener(v -> rootToggle(app.packageName, true));
        rootActions.addView(unfreeze, weightWrap());
        box.addView(rootActions, matchWrap());
        return box;
    }

    private void rootToggle(String packageName, boolean enable) {
        io.execute(() -> {
            RootShell.Result r = enable ? RootShell.enablePackage(packageName) : RootShell.disablePackage(packageName);
            runOnUiThread(() -> Toast.makeText(this, (enable ? "Unfreeze: " : "Freeze: ") + (r.ok() ? "OK" : r.output.trim()), Toast.LENGTH_LONG).show());
        });
    }

    private void launchProxy(String packageName, String label) {
        saveSettingsFromUi();
        Intent i = ProxyActivity.intentFor(this, packageName, label);
        startActivity(i);
    }

    private void pinShortcut(String packageName, String label) {
        saveSettingsFromUi();
        Intent shortcutIntent = ProxyActivity.intentFor(this, packageName, label);
        shortcutIntent.setAction(Intent.ACTION_VIEW);

        if (Build.VERSION.SDK_INT >= 26) {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                Toast.makeText(this, "Your launcher does not support pinned shortcuts.", Toast.LENGTH_LONG).show();
                return;
            }
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "freeze_" + packageName)
                    .setShortLabel(label.length() > 20 ? label.substring(0, 20) : label)
                    .setLongLabel("Freeze shortcut: " + label)
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher_foreground))
                    .setIntent(shortcutIntent)
                    .build();
            sm.requestPinShortcut(shortcut, null);
            Toast.makeText(this, "Shortcut request sent to launcher.", Toast.LENGTH_SHORT).show();
        } else {
            Intent add = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            add.putExtra(Intent.EXTRA_SHORTCUT_NAME, label);
            add.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
            add.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_launcher_foreground));
            sendBroadcast(add);
            Toast.makeText(this, "Legacy shortcut broadcast sent.", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }
}
