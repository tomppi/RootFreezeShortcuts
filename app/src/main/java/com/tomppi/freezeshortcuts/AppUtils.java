package com.tomppi.freezeshortcuts;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AppUtils {
    private AppUtils() {}

    public static final class AppEntry {
        public final String label;
        public final String packageName;
        public final boolean systemApp;
        public final boolean enabled;

        AppEntry(String label, String packageName, boolean systemApp, boolean enabled) {
            this.label = label;
            this.packageName = packageName;
            this.systemApp = systemApp;
            this.enabled = enabled;
        }
    }

    public static List<AppEntry> loadLaunchableApps(Context context, boolean includeSystem) {
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
        List<ApplicationInfo> installed;
        if (Build.VERSION.SDK_INT >= 33) {
            installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags));
        } else {
            installed = pm.getInstalledApplications(flags);
        }

        List<AppEntry> out = new ArrayList<>();
        String self = context.getPackageName();
        for (ApplicationInfo ai : installed) {
            if (ai == null || ai.packageName == null || ai.packageName.equals(self)) continue;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && !includeSystem) continue;
            if (buildLaunchIntent(context, ai.packageName, true) == null) continue;
            CharSequence labelSeq = ai.loadLabel(pm);
            String label = labelSeq == null ? ai.packageName : labelSeq.toString();
            out.add(new AppEntry(label, ai.packageName, system, ai.enabled));
        }
        Collections.sort(out, Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
        return out;
    }

    public static Intent buildLaunchIntent(Context context, String packageName, boolean includeDisabled) {
        if (!RootShell.isValidPackageName(packageName)) return null;
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        query.setPackage(packageName);
        int flags = includeDisabled ? PackageManager.MATCH_DISABLED_COMPONENTS : 0;
        List<ResolveInfo> matches;
        if (Build.VERSION.SDK_INT >= 33) {
            matches = pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(flags));
        } else {
            matches = pm.queryIntentActivities(query, flags);
        }
        if (matches == null || matches.isEmpty()) {
            return pm.getLaunchIntentForPackage(packageName);
        }
        ResolveInfo ri = matches.get(0);
        if (ri.activityInfo == null) return null;
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return launch;
    }

    public static String humanState(AppEntry entry) {
        String state = entry.enabled ? "enabled" : "disabled/frozen";
        return entry.systemApp ? state + ", system" : state;
    }
}
