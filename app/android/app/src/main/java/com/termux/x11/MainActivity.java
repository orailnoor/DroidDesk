package com.termux.x11;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;

public class MainActivity extends Activity {
    public static final Handler handler = new Handler(Looper.getMainLooper());
    private static final MainActivity instance = new MainActivity();
    
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";
    
    private LorieView lorieView;
    private long lorieGeneration = -1;
    public interface KeyHandler {
        boolean handle(KeyEvent event);
    }
    private KeyHandler keyHandler;

    public boolean useTermuxEKBarBehaviour = false;
    public static class DummyExtraKeys {
        public void unsetSpecialKeys() {}
    }
    public DummyExtraKeys mExtraKeys = null;

    public static MainActivity getInstance() {
        return instance;
    }

    public LorieView getLorieView() {
        return lorieView;
    }

    public LorieView attachLorieView(Context context, long generation) {
        if (getBaseContext() == null) {
            attachBaseContext(context.getApplicationContext());
        }
        if (lorieView != null)
            releaseLorieView(lorieGeneration);
        lorieView = new LorieView(context);
        lorieGeneration = generation;
        lorieView.resetRendererZoom();
        return lorieView;
    }

    public boolean isLorieViewOwner(long generation) {
        return lorieView != null && lorieGeneration == generation;
    }

    public void releaseLorieView(long generation) {
        if (!isLorieViewOwner(generation))
            return;
        lorieView.releaseRenderer();
        lorieView = null;
        lorieGeneration = -1;
        keyHandler = null;
        mExtraKeys = null;
        useTermuxEKBarBehaviour = false;
    }

    @Override
    public Resources getResources() {
        if (lorieView != null)
            return lorieView.getContext().getResources();
        Context base = getBaseContext();
        return base != null ? base.getResources() : Resources.getSystem();
    }

    @Override
    public android.view.WindowManager getWindowManager() {
        Context base = lorieView != null ? lorieView.getContext() : getBaseContext();
        if (base != null) {
            return (android.view.WindowManager) base.getSystemService(Context.WINDOW_SERVICE);
        }
        return super.getWindowManager();
    }

    @Override
    public android.content.ComponentName getComponentName() {
        if (lorieView != null && lorieView.getContext() instanceof Activity)
            return ((Activity) lorieView.getContext()).getComponentName();
        return new android.content.ComponentName(this, MainActivity.class);
    }

    public static boolean isConnected() {
        return LorieView.connected();
    }
    
    public static void toggleKeyboardVisibility(Context context) {}
    public void toggleExtraKeys() {}
    public void toggleMouseHelper() {}
    
    public static void setCapturingEnabled(boolean e) {}
    public void setExternalKeyboardConnected(boolean c) {}
    
    public static void getRealMetrics(DisplayMetrics metrics) {
        if (instance.lorieView != null) {
            metrics.setTo(instance.lorieView.getResources().getDisplayMetrics());
            if (instance.lorieView.getMeasuredWidth() > 0 && instance.lorieView.getMeasuredHeight() > 0) {
                metrics.widthPixels = instance.lorieView.getMeasuredWidth();
                metrics.heightPixels = instance.lorieView.getMeasuredHeight();
            }
        } else if (instance.getBaseContext() != null) {
            metrics.setTo(instance.getResources().getDisplayMetrics());
        } else {
            metrics.setTo(Resources.getSystem().getDisplayMetrics());
        }
    }
    
    public static final Prefs prefs = new Prefs();
    
    public static Prefs getPrefs() {
        return prefs;
    }
    
    public boolean handleKey(KeyEvent event) {
        // Let Android dismiss the soft keyboard and handle system navigation.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK)
            return false;
        return keyHandler != null && keyHandler.handle(event);
    }

    public void setKeyHandler(KeyHandler handler, long generation) {
        if (isLorieViewOwner(generation))
            keyHandler = handler;
    }

    // JNI STUBS
    public void clientConnectedStateChanged() {
        android.util.Log.e("MainActivity", "clientConnectedStateChanged called by native code!");
        final long generation = lorieGeneration;
        for (long delay : new long[] {200, 500, 1000, 2000})
            handler.postDelayed(() -> {
                if (isLorieViewOwner(generation))
                    lorieView.refreshRenderer();
            }, delay);
    }
    public void showToast(String text) {}
    public void updateNotification() {}
}
