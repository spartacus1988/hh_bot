package ru.hhbot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;

/** Поиск установленного приложения hh.ru. */
public final class AppFinder {

    /** Официальные пакеты hh.ru (обычное приложение, не «hh бизнес»). */
    private static final String[] KNOWN_PACKAGES = {
            "ru.hh.android",
            "com.hh.android",
            "com.headhunter.android",
            "ru.hh"
    };

    private AppFinder() {
    }

    public static String findHhPackage(Context context) {
        PackageManager pm = context.getPackageManager();

        for (String pkg : KNOWN_PACKAGES) {
            if (isInstalled(pm, pkg)) {
                return pkg;
            }
        }

        // Резервный поиск: вдруг пакет переименовали.
        try {
            for (ApplicationInfo info : installedApps(pm)) {
                String pkg = info.packageName == null
                        ? "" : info.packageName.toLowerCase(Locale.ROOT);
                String label;
                try {
                    label = String.valueOf(pm.getApplicationLabel(info))
                            .toLowerCase(Locale.ROOT);
                } catch (Throwable t) {
                    label = "";
                }
                if (pkg.equals("ru.hh.employer.android")) {
                    continue; // это приложение для работодателей
                }
                if (pkg.contains("headhunter")
                        || pkg.startsWith("ru.hh.")
                        || pkg.equals("ru.hh")
                        || label.contains("hh.ru")
                        || label.contains("headhunter")
                        || label.contains("хедхантер")
                        || label.startsWith("hh:")) {
                    return info.packageName;
                }
            }
        } catch (Throwable ignored) {
            // ниже вернём null
        }
        return null;
    }

    public static boolean isInstalled(Context context, String pkg) {
        return pkg != null && isInstalled(context.getPackageManager(), pkg);
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static String versionName(Context context, String pkg) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    private static java.util.List<ApplicationInfo> installedApps(PackageManager pm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0));
        }
        return pm.getInstalledApplications(0);
    }
}
