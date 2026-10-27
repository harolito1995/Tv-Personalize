package com.harolhome.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Opt-in accessibility helper (install with -HomeKeyService). Some firmware keeps opening its stock launcher
 * on the Home key even though HarolHome is the Home app. With stock_home_redirect set (config values/home_key.xml)
 * the stock launcher stays enabled, which is safe on boards where disabling it can boot-loop the TV, and
 * this service sends its home screen straight back to HarolHome. It reads no key but Home, shows nothing and
 * uses no network.
 */
public class HomeKeyService extends AccessibilityService {

    /** Lets the system see the key first, so a long press still does what the firmware gives it. */
    private static final long HOME_KEY_DELAY_MS = 250;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The stock launcher's HOME activities, disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        stockHome.clear();
        String stock = getString(R.string.stock_home_redirect).trim();
        if (stock.isEmpty()) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(stock);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            stockHome.add(ri.activityInfo.name);
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && !stockHome.isEmpty()) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME || stockHome.isEmpty()) return false;
        // A short press opens HarolHome shortly after the system handled the key; a long press is left alone.
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            handler.postDelayed(this::openHome, HOME_KEY_DELAY_MS);
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = event.getClassName();
        if (!stockHome.isEmpty() && cls != null && stockHome.contains(cls.toString())) openHome();
    }

    private void openHome() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}