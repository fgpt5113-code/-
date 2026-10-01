package io.local.phoneuxlogger;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class UxAccessibilityService extends AccessibilityService {
    public static final String ACTION_RAW_TEST = "io.local.phoneuxlogger.ACTION_RAW_TEST_10S";
    private static final long RAW_TEST_MS = 10_000L;

    private LogStore store;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean rawMode;
    private boolean receiverRegistered;
    private final Runnable rawStopRunnable = this::disableRawTest;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_RAW_TEST.equals(action)) {
                enableRawTest();
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_SCREEN_OFF.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)) {
                ContentValues v = base("system");
                v.put("event_name", action);
                v.put("meta", batteryMeta());
                store.insertAsync(v);
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        store = LogStore.get(this);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_RAW_TEST);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
        }

        ContentValues v = base("service");
        v.put("event_name", "CONNECTED");
        v.put("meta", "sdk=" + Build.VERSION.SDK_INT + ";" + batteryMeta());
        store.insertAsync(v);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (store == null || !loggingEnabled()) return;
        if (!verboseEnabled() && !isCoreEvent(event.getEventType())) return;

        ContentValues v = base("accessibility");
        v.put("event_type", event.getEventType());
        v.put("event_name", AccessibilityEvent.eventTypeToString(event.getEventType()));
        putString(v, "package_name", event.getPackageName());
        putString(v, "class_name", event.getClassName());
        v.put("window_id", event.getWindowId());
        v.put("scroll_x", event.getScrollX());
        v.put("scroll_y", event.getScrollY());
        v.put("max_scroll_x", event.getMaxScrollX());
        v.put("max_scroll_y", event.getMaxScrollY());
        v.put("from_index", event.getFromIndex());
        v.put("to_index", event.getToIndex());
        v.put("item_count", event.getItemCount());
        v.put("action", event.getAction());

        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source != null) {
                Rect r = new Rect();
                source.getBoundsInScreen(r);
                v.put("left_px", r.left);
                v.put("top_px", r.top);
                v.put("right_px", r.right);
                v.put("bottom_px", r.bottom);
                if (!r.isEmpty()) {
                    v.put("x_px", (r.left + r.right) / 2.0);
                    v.put("y_px", (r.top + r.bottom) / 2.0);
                }
                String id = source.getViewIdResourceName();
                if (id != null) v.put("view_id", id);
                v.put("meta", "coord=source_bounds_center;password=" + source.isPassword()
                        + ";clickable=" + source.isClickable()
                        + ";scrollable=" + source.isScrollable());
            } else {
                v.put("meta", "coord=unavailable");
            }
        } catch (Throwable t) {
            v.put("meta", "coord=unavailable;source_error=" + t.getClass().getSimpleName());
        }

        // Intentionally never read event.getText(), getBeforeText(), node text,
        // contentDescription or notification payloads.
        store.insertAsync(v);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (store != null && loggingEnabled()) {
            ContentValues v = base("key");
            v.put("event_name", keyActionToString(event.getAction()));
            v.put("action", event.getAction());
            // Key code only. Unicode/text is deliberately not stored.
            v.put("meta", "keyCode=" + event.getKeyCode()
                    + ";repeat=" + event.getRepeatCount()
                    + ";deviceId=" + event.getDeviceId());
            store.insertAsync(v);
        }
        return false; // Never consume the key.
    }

    private static String keyActionToString(int action) {
        switch (action) {
            case KeyEvent.ACTION_DOWN: return "ACTION_DOWN";
            case KeyEvent.ACTION_UP: return "ACTION_UP";
            case KeyEvent.ACTION_MULTIPLE: return "ACTION_MULTIPLE";
            default: return "ACTION_" + action;
        }
    }

    @Override public void onMotionEvent(MotionEvent event) {
        super.onMotionEvent(event);
        if (!rawMode || store == null || !loggingEnabled() || Build.VERSION.SDK_INT < 34) return;
        int actionMasked = event.getActionMasked();
        for (int i = 0; i < event.getPointerCount(); i++) {
            ContentValues v = base("raw_touch");
            v.put("event_type", actionMasked);
            v.put("event_name", MotionEvent.actionToString(event.getAction()));
            v.put("action", event.getAction());
            v.put("x_px", event.getX(i));
            v.put("y_px", event.getY(i));
            v.put("meta", "pointerId=" + event.getPointerId(i)
                    + ";pressure=" + event.getPressure(i)
                    + ";size=" + event.getSize(i)
                    + ";source=" + event.getSource());
            store.insertAsync(v);
        }
    }

    @Override public void onInterrupt() {
        if (store != null) {
            ContentValues v = base("service");
            v.put("event_name", "INTERRUPTED");
            store.insertAsync(v);
        }
    }

    @Override public void onDestroy() {
        disableRawTest();
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private void enableRawTest() {
        if (Build.VERSION.SDK_INT < 34 || rawMode || store == null) return;
        AccessibilityServiceInfo info = getServiceInfo();
        info.setMotionEventSources(InputDevice.SOURCE_TOUCHSCREEN);
        setServiceInfo(info);
        rawMode = true;
        ContentValues v = base("service");
        v.put("event_name", "RAW_TOUCH_TEST_START");
        v.put("meta", "duration_ms=" + RAW_TEST_MS + ";touchscreen_input_is_consumed_during_test=true");
        store.insertAsync(v);
        mainHandler.removeCallbacks(rawStopRunnable);
        mainHandler.postDelayed(rawStopRunnable, RAW_TEST_MS);
    }

    private void disableRawTest() {
        if (Build.VERSION.SDK_INT >= 34 && rawMode) {
            try {
                AccessibilityServiceInfo info = getServiceInfo();
                info.setMotionEventSources(0);
                setServiceInfo(info);
            } catch (Throwable ignored) {}
            rawMode = false;
            if (store != null) {
                ContentValues v = base("service");
                v.put("event_name", "RAW_TOUCH_TEST_END");
                store.insertAsync(v);
            }
        }
    }

    private boolean loggingEnabled() {
        return prefs == null || prefs.getBoolean("logging_enabled", true);
    }

    private boolean verboseEnabled() {
        return prefs != null && prefs.getBoolean("verbose_enabled", false);
    }

    private static boolean isCoreEvent(int t) {
        return t == AccessibilityEvent.TYPE_VIEW_CLICKED
                || t == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                || t == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED
                || t == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || t == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || t == AccessibilityEvent.TYPE_VIEW_SELECTED
                || t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || t == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                || t == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
                || t == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
    }

    private ContentValues base(String kind) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis());
        v.put("kind", kind);
        return v;
    }

    private static void putString(ContentValues v, String key, CharSequence value) {
        if (value != null) v.put(key, value.toString());
    }

    private String batteryMeta() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return "battery=unknown";
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            float pct = scale > 0 ? level * 100f / scale : -1f;
            return "battery_pct=" + pct + ";battery_temp_c=" + (temp / 10f) + ";battery_status=" + status;
        } catch (Throwable t) {
            return "battery=unavailable";
        }
    }
}
