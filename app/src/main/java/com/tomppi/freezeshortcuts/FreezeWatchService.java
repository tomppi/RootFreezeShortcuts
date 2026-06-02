package com.tomppi.freezeshortcuts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FreezeWatchService extends Service {
    private static final String CHANNEL_ID = "freeze_watch";
    private static final int NOTIFICATION_ID = 42042;
    private static final String ACTION_FREEZE_NOW = "com.tomppi.freezeshortcuts.FREEZE_NOW";
    private static final String EXTRA_PACKAGE = "target_package";
    private static final String EXTRA_LABEL = "target_label";
    private static final String EXTRA_GRACE_SECONDS = "grace_seconds";
    private static final String EXTRA_LAUNCH_TIMEOUT_SECONDS = "launch_timeout_seconds";

    private static final Pattern PACKAGE_BEFORE_SLASH = Pattern.compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/");
    private static final long POLL_MS = 5000;

    private final Map<String, Thread> workers = new ConcurrentHashMap<>();

    public static Intent intentFor(Context context, String packageName, String label, int graceSeconds, int launchTimeoutSeconds) {
        Intent i = new Intent(context, FreezeWatchService.class);
        i.putExtra(EXTRA_PACKAGE, packageName);
        i.putExtra(EXTRA_LABEL, label == null ? packageName : label);
        i.putExtra(EXTRA_GRACE_SECONDS, graceSeconds);
        i.putExtra(EXTRA_LAUNCH_TIMEOUT_SECONDS, launchTimeoutSeconds);
        return i;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        if (!RootShell.isValidPackageName(pkg)) return START_NOT_STICKY;

        if (ACTION_FREEZE_NOW.equals(intent.getAction())) {
            freezeAndStop(pkg);
            return START_NOT_STICKY;
        }

        String label = intent.getStringExtra(EXTRA_LABEL);
        if (label == null) label = pkg;
        int graceSeconds = Math.max(1, intent.getIntExtra(EXTRA_GRACE_SECONDS, 20));
        int launchTimeoutSeconds = Math.max(10, intent.getIntExtra(EXTRA_LAUNCH_TIMEOUT_SECONDS, 90));

        startForeground(NOTIFICATION_ID, buildNotification("Watching " + label, pkg));
        if (!workers.containsKey(pkg)) {
            final String targetLabel = label;
            Thread t = new Thread(() -> watchLoop(pkg, targetLabel, graceSeconds * 1000L, launchTimeoutSeconds * 1000L), "freeze-watch-" + pkg);
            workers.put(pkg, t);
            t.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        for (Thread t : workers.values()) t.interrupt();
        workers.clear();
        super.onDestroy();
    }

    private void watchLoop(String packageName, String label, long graceMs, long launchTimeoutMs) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RootFreezeShortcuts:watch");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(Math.max(launchTimeoutMs + graceMs + 30000, 180000));
            }

            boolean seenTarget = false;
            long started = SystemClock.elapsedRealtime();
            long lastSeenTarget = 0L;

            while (!Thread.currentThread().isInterrupted()) {
                String top = getTopPackage();
                long now = SystemClock.elapsedRealtime();

                if (packageName.equals(top)) {
                    seenTarget = true;
                    lastSeenTarget = now;
                } else if (seenTarget && now - lastSeenTarget >= graceMs) {
                    break;
                } else if (!seenTarget && now - started >= launchTimeoutMs) {
                    break;
                }

                SystemClock.sleep(POLL_MS);
            }

            RootShell.disablePackage(packageName);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            workers.remove(packageName);
            if (workers.isEmpty()) {
                stopForegroundCompat();
                stopSelf();
            }
        }
    }

    private void freezeAndStop(String packageName) {
        Thread t = workers.remove(packageName);
        if (t != null) t.interrupt();
        new Thread(() -> {
            RootShell.disablePackage(packageName);
            if (workers.isEmpty()) {
                stopForegroundCompat();
                stopSelf();
            }
        }, "freeze-now").start();
    }

    private String getTopPackage() {
        String command = "(dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 8) 2>/dev/null";
        RootShell.Result r = RootShell.run(command, 15000);
        String parsed = parsePackage(r.output);
        if (parsed != null) return parsed;

        RootShell.Result w = RootShell.run("(dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 5) 2>/dev/null", 15000);
        return parsePackage(w.output);
    }

    private String parsePackage(String text) {
        if (text == null) return null;
        Matcher m = PACKAGE_BEFORE_SLASH.matcher(text);
        while (m.find()) {
            String pkg = m.group(1);
            if (RootShell.isValidPackageName(pkg) && !pkg.equals(getPackageName())) return pkg;
        }
        return null;
    }

    private Notification buildNotification(String title, String packageName) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent freezeNow = new Intent(this, FreezeWatchService.class);
        freezeNow.setAction(ACTION_FREEZE_NOW);
        freezeNow.putExtra(EXTRA_PACKAGE, packageName);
        PendingIntent freezePi = PendingIntent.getService(this, packageName.hashCode(), freezeNow, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText("Will freeze after you leave it. Tap action to freeze now.")
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(R.drawable.ic_notification, "Freeze now", freezePi);
        if (Build.VERSION.SDK_INT >= 21) b.setCategory(Notification.CATEGORY_SERVICE);
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Freeze watcher", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps the root app alive long enough to refreeze launched apps.");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
    }
}
