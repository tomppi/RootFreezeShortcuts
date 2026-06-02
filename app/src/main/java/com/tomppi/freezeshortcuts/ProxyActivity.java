package com.tomppi.freezeshortcuts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class ProxyActivity extends Activity {
    public static final String EXTRA_PACKAGE = "target_package";
    public static final String EXTRA_LABEL = "target_label";

    private TextView status;

    public static Intent intentFor(Context context, String packageName, String label) {
        Intent i = new Intent(context, ProxyActivity.class);
        i.putExtra(EXTRA_PACKAGE, packageName);
        i.putExtra(EXTRA_LABEL, label == null ? packageName : label);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);
        status = new TextView(this);
        status.setTextSize(18);
        status.setText("Preparing…");
        root.addView(status);
        setContentView(root);

        String pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        if (!RootShell.isValidPackageName(pkg)) {
            fail("Invalid package: " + pkg);
            return;
        }
        if (label == null || label.trim().isEmpty()) label = pkg;
        final String targetLabel = label;
        new Thread(() -> runProxy(pkg, targetLabel), "proxy-launch").start();
    }

    private void runProxy(String packageName, String label) {
        setStatus("Unfreezing " + label + "…");
        RootShell.Result rootCheck = RootShell.hasRoot();
        if (!RootShell.outputLooksRoot(rootCheck)) {
            fail("Root was denied or unavailable.\n" + rootCheck.output.trim());
            return;
        }

        RootShell.Result enable = RootShell.enablePackage(packageName);
        if (!enable.ok()) {
            fail("Could not unfreeze " + packageName + "\n" + enable.output.trim());
            return;
        }

        SystemClock.sleep(900);
        Intent launch = AppUtils.buildLaunchIntent(this, packageName, true);
        if (launch == null) {
            fail("Unfroze the package, but no launcher activity was found for " + packageName);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        boolean auto = prefs.getBoolean(MainActivity.PREF_AUTO_REFREEZE, true);
        int grace = prefs.getInt(MainActivity.PREF_GRACE_SECONDS, 20);
        int timeout = prefs.getInt(MainActivity.PREF_MAX_WAIT_SECONDS, 90);

        if (auto) {
            Intent watch = FreezeWatchService.intentFor(this, packageName, label, grace, timeout);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(watch); else startService(watch);
        }

        setStatus("Launching " + label + "…");
        runOnUiThread(() -> {
            try {
                startActivity(launch);
                finishAndRemoveTaskCompat();
            } catch (Exception e) {
                fail("Launch failed: " + e.getMessage());
            }
        });
    }

    private void setStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private void fail(String text) {
        runOnUiThread(() -> {
            status.setText(text);
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        });
    }

    private void finishAndRemoveTaskCompat() {
        if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask(); else finish();
    }
}
