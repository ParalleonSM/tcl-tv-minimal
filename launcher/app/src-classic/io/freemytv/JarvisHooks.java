package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.view.KeyEvent;

/**
 * The classic build (./build.sh --flavor classic): no JARVIS at all. Every hook is a no-op, so the home screen is
 * exactly Free My TV's minimalist layout. The jarvis build has the real one in app/src-jarvis/.
 */
final class JarvisHooks {
    private JarvisHooks() {}

    static final boolean AVAILABLE = false;

    static void registerActions() {}
    static void onHomeCreate(Activity a) {}
    static void onHomeResume(Activity a) {}
    static void onHomePause(Activity a) {}
    static void onHomeDestroy(Activity a) {}
    static boolean onHomeKey(Activity a, KeyEvent e) { return false; }
    static void setEnabled(Context c, boolean on) {}
}
