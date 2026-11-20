package com.weworkshop.statusbarfix;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Makes the status bar follow a live wallpaper's own dark/light text hint
 * ({@link WallpaperColors#HINT_SUPPORTS_DARK_TEXT}) on the home screen and the lock screen.
 *
 * <p>ZTE MiFavor ignores that hint for live wallpapers: the launcher judges the color from a
 * screenshot of one frame and resets it to the theme default when the workspace rebinds, and the
 * lock screen (ZTE's SystemUI) defaults to white for its status bar, clock and widgets. On a light video wallpaper that leaves white,
 * unreadable status bar text. Static wallpapers are left to the system, which handles them.
 *
 * <ul>
 *   <li>Launcher process: Launcher3-based launchers apply the color with
 *   {@link View#setSystemUiVisibility(int)} on their window's root view, so the
 *   light-status-bar flag is adjusted there.</li>
 *   <li>SystemUI process: ZTE's lock screen sets its status bar color through
 *   {@code KeyguardDarkIconDispatcher.setKeyguardStatusBarForeColor} and the color of its clock,
 *   widgets and shortcuts through {@code MfvKeyguardStyle.setColor}; both are replaced with dark
 *   or light (except on the always-on display, or when the user picked a clock color).</li>
 * </ul>
 */
public class LauncherStatusBarHook implements IXposedHookLoadPackage {
    private static final String TAG = "StatusBarFix: ";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String ZTE_KEYGUARD_ICONS = "com.zte.mifavor.policy.KeyguardDarkIconDispatcher";
    private static final String ZTE_KEYGUARD_STYLE = "com.zte.mifavor.keyguard.MfvKeyguardStyle";
    private static final int LIGHT_STATUS_BAR = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
    private static final int DARK_FORE = 0xFF000000;
    private static final int LIGHT_FORE = 0xFFFFFFFF;
    /** ZTE's dark lock screen content color (see ColorUtils.computeForeColor). */
    private static final int DARK_CONTENT = 0xA6000000;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) return;
        if (SYSTEMUI.equals(lpparam.packageName)) {
            hookLockScreen(lpparam.classLoader);
        } else {
            hookLauncher();
        }
        XposedBridge.log(TAG + "loaded in " + lpparam.packageName);
    }

    // region launcher (home screen)

    /** true = dark icons, false = light icons, null = leave the launcher's choice alone. */
    private static volatile Boolean homeDarkText;
    private static boolean homeListening;
    /** Root views the launcher styled, with the flags it asked for (before our change). */
    private static final Map<View, Integer> requested = new WeakHashMap<>();

    private static void hookLauncher() {
        XposedHelpers.findAndHookMethod(View.class, "setSystemUiVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                if (view.getRootView() != view) return; // only the window's own flags
                int flags = (Integer) param.args[0];
                requested.put(view, flags);
                if (!homeListening) {
                    homeListening = true;
                    watch(view.getContext(), WallpaperManager.FLAG_SYSTEM, () -> {
                        homeDarkText = darkTextHint(view.getContext(), WallpaperManager.FLAG_SYSTEM);
                        for (Map.Entry<View, Integer> e : new ArrayList<>(requested.entrySet())) {
                            View v = e.getKey();
                            if (v != null && v.isAttachedToWindow()) v.setSystemUiVisibility(e.getValue());
                        }
                    });
                    homeDarkText = darkTextHint(view.getContext(), WallpaperManager.FLAG_SYSTEM);
                }
                Boolean dark = homeDarkText;
                if (dark != null) param.args[0] = dark ? (flags | LIGHT_STATUS_BAR) : (flags & ~LIGHT_STATUS_BAR);
            }
        });
    }

    // endregion

    // region lock screen (ZTE SystemUI)

    private static volatile Boolean lockDarkText;
    private static boolean lockListening;
    /** True while the always-on display shows the clock on black: keep it light then. */
    private static volatile boolean dozing;
    /** The last call of each hooked lock screen method, replayed when the wallpaper colors change. */
    private static final Map<Method, Object[]> lastCalls = new java.util.LinkedHashMap<>();
    private static final Map<Method, Object> lastTargets = new java.util.HashMap<>();

    private static void hookLockScreen(ClassLoader classLoader) {
        // Status bar icons: black or white.
        XC_MethodHook statusBar = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                remember(param);
                Boolean dark = lockHint();
                if (dark != null) param.args[0] = dark ? DARK_FORE : LIGHT_FORE;
            }
        };
        // Clock, date, lock screen widgets, shortcuts and other content: ZTE uses 65% black for dark.
        XC_MethodHook content = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                remember(param);
                if (dozing || userPickedClockColor()) return;
                Boolean dark = lockHint();
                if (dark != null) param.args[0] = dark ? DARK_CONTENT : LIGHT_FORE;
            }
        };
        XC_MethodHook dozingState = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                dozing = (Boolean) param.args[0];
            }
        };
        hook(ZTE_KEYGUARD_ICONS, classLoader, "setKeyguardStatusBarForeColor", int.class, statusBar);
        hook(ZTE_KEYGUARD_ICONS, classLoader, "setKeyguardStatusBarForeColor", int.class, boolean.class, statusBar);
        hook(ZTE_KEYGUARD_STYLE, classLoader, "setColor", int.class, content);
        hook(ZTE_KEYGUARD_STYLE, classLoader, "updateStatusViewDozing", boolean.class, dozingState);
    }

    private static void hook(String className, ClassLoader classLoader, String method, Object... typesAndCallback) {
        try {
            XposedHelpers.findAndHookMethod(className, classLoader, method, typesAndCallback);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "can't hook " + className + "." + method + ": " + t);
        }
    }

    /** Records the call (with its original arguments) and starts watching the wallpaper once. */
    private static void remember(XC_MethodHook.MethodHookParam param) {
        Method method = (Method) param.method;
        synchronized (lastCalls) {
            lastCalls.put(method, param.args.clone());
            lastTargets.put(method, param.thisObject);
        }
        if (lockListening) return;
        Context context = currentApplication();
        if (context == null) return;
        lockListening = true;
        lockDarkText = lockScreenHint(context);
        watch(context, WallpaperManager.FLAG_LOCK | WallpaperManager.FLAG_SYSTEM, () -> {
            lockDarkText = lockScreenHint(context);
            replayLockScreen();
        });
    }

    private static Boolean lockHint() {
        return lockListening ? lockDarkText : null;
    }

    /** Re-runs the last color calls so the clock, widgets and status bar pick up the new hint. */
    private static void replayLockScreen() {
        List<Map.Entry<Method, Object[]>> calls;
        synchronized (lastCalls) {
            calls = new ArrayList<>(lastCalls.entrySet());
        }
        for (Map.Entry<Method, Object[]> call : calls) {
            Object target;
            synchronized (lastCalls) {
                target = lastTargets.get(call.getKey());
            }
            try {
                call.getKey().setAccessible(true);
                call.getKey().invoke(target, call.getValue().clone()); // the hook applies the new hint
            } catch (Throwable t) {
                XposedBridge.log(TAG + "can't refresh the lock screen: " + t);
            }
        }
    }

    /** ZTE's clock style settings; a color the user chose there is left alone. */
    private static boolean userPickedClockColor() {
        try {
            Context context = currentApplication();
            if (context == null) return false;
            String style = android.provider.Settings.System.getString(context.getContentResolver(), "MY_CLOCK_STYLE");
            return style != null && new org.json.JSONObject(style).optBoolean("colorChangedByUser", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The lock screen's live wallpaper hint: its own if it has one, else the home screen's. */
    private static Boolean lockScreenHint(Context context) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context);
            boolean lockIsLive = wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK) != null;
            boolean lockIsSeparateImage = !lockIsLive && wm.getWallpaperId(WallpaperManager.FLAG_LOCK) > 0;
            if (lockIsSeparateImage) return null; // a static lock screen image: the system handles it
            return lockIsLive ? darkTextHint(context, WallpaperManager.FLAG_LOCK) : darkTextHint(context, WallpaperManager.FLAG_SYSTEM);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "can't read the lock screen wallpaper: " + t);
            return null;
        }
    }

    private static Context currentApplication() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // endregion

    /** The live wallpaper's dark-text hint for [which], or null for static or unknown wallpapers. */
    private static Boolean darkTextHint(Context context, int which) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context.getApplicationContext());
            if (wm.getWallpaperInfo(which) == null && wm.getWallpaperInfo() == null) return null;
            WallpaperColors colors = wm.getWallpaperColors(which);
            if (colors == null) return null;
            return (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "can't read wallpaper colors: " + t);
            return null;
        }
    }

    /** Calls [onChange] on the main thread whenever the wallpaper or its colors change. */
    private static void watch(Context context, int which, Runnable onChange) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context.getApplicationContext());
            wm.addOnColorsChangedListener((colors, changed) -> {
                if ((changed & which) != 0) onChange.run();
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "can't watch wallpaper colors: " + t);
        }
    }
}
