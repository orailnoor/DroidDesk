package com.termux.x11;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;

public class MainActivity extends Activity {
    public static final Handler handler = new Handler(Looper.getMainLooper());
    private static final MainActivity instance = new MainActivity();
    
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";
    
    private LorieView lorieView;
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

    public void initLorieView(Context context) {
        if (getBaseContext() == null) {
            attachBaseContext(context);
        }
        if (lorieView == null) {
            lorieView = new LorieView(context);
        }
    }

    @Override
    public android.view.WindowManager getWindowManager() {
        Context base = getBaseContext();
        if (base != null) {
            return (android.view.WindowManager) base.getSystemService(Context.WINDOW_SERVICE);
        }
        return super.getWindowManager();
    }

    @Override
    public android.content.ComponentName getComponentName() {
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
        if (instance.getBaseContext() != null) {
            instance.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics.widthPixels = 1920;
            metrics.heightPixels = 1080;
            metrics.density = 2.0f;
            metrics.densityDpi = 320;
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

    public void setKeyHandler(KeyHandler handler) {
        keyHandler = handler;
    }

    // JNI STUBS
    public void clientConnectedStateChanged() {
        android.util.Log.e("MainActivity", "clientConnectedStateChanged called by native code!");
        if (lorieView != null) {
            handler.postDelayed(() -> {
                android.util.Log.e("MainActivity", "Executing reload from MainActivity handler!");
                lorieView.reload();
            }, 200);
            handler.postDelayed(() -> lorieView.reload(), 500);
            handler.postDelayed(() -> lorieView.reload(), 1000);
            handler.postDelayed(() -> lorieView.reload(), 2000);
        } else {
            android.util.Log.e("MainActivity", "lorieView is NULL in MainActivity!");
        }
    }
    public void showToast(String text) {}
    public void updateNotification() {}
}
