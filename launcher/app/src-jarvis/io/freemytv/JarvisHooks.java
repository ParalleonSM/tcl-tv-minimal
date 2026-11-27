package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Everything the JARVIS layer adds to the home screen, behind one flag (config.json jarvis.enabled, the Settings row,
 * `tvctl.sh jarvis on|off`). HomeActivity calls these hooks and nothing else; with the flag off every hook does nothing
 * and the home screen is the classic one, exactly. The classic build (app/src-classic) has the same hooks, empty.
 *
 * What it adds while on:
 *  - the HUD pill at the top (JarvisHud), as a view over the home screen;
 *  - wake: holding Back on the home screen, or any key code in jarvis.wake.keys, opens the voice mode (JarvisActivity);
 *  - the "speak" and "listen" actions in the command layer, so an agent can talk through JARVIS;
 *  - jarvis.theme: colours over the classic theme (UserConfig.color reads them only while JARVIS is on);
 *  - the "JARVIS" entry in the TV's app list (an activity-alias), so "Hey Google, open JARVIS" can reach it.
 */
final class JarvisHooks {
    private JarvisHooks() {}

    static final boolean AVAILABLE = true;

    private static final Map<Activity, JarvisHud> HUDS = new WeakHashMap<>();
    /** Back is held: its repeats and release are ours, not the home screen's. */
    private static boolean holdingBack;

    static void registerActions() {
        Commands.register("speak", "JARVIS says it aloud (TTS). text=<what to say>", (c, a, r) -> {
            String t = a.optString("text", "").trim();
            if (t.isEmpty()) { r.put("ok", false).put("say", "Say what?"); return; }
            Voice.get(c).say(t, null);
            r.put("ok", true).put("said", t).put("say", t);
        });
        Commands.register("listen", "Open JARVIS's voice mode on the TV", (c, a, r) -> {
            if (!UserConfig.jarvisEnabled(c)) { r.put("ok", false).put("say", "JARVIS is off (classic layout)."); return; }
            c.startActivity(new Intent(c, JarvisActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            r.put("ok", true).put("say", "Listening on the TV.");
        });
        Commands.register("voices", "The TTS voice names this TV offers (for jarvis.voice.name)", (c, a, r) ->
                r.put("ok", true).put("voices", Voice.get(c).voices()).put("say", "Voices listed."));
    }

    static Persona persona(Context c) {
        JSONObject j = UserConfig.jarvis(c);
        return new Persona(j.optString("name", "JARVIS"), j.optString("address", "sir"), null);
    }

    static void onHomeCreate(Activity a) {
        refresh(a);
    }

    /** HUD in or out, to match the flag. */
    private static void refresh(Activity a) {
        boolean on = UserConfig.jarvisEnabled(a) && UserConfig.jarvis(a).optBoolean("hud", true);
        JarvisHud hud = HUDS.get(a);
        if (on && hud == null) {
            int w = a.getResources().getDisplayMetrics().widthPixels;
            float k = w / 1920f;
            int accent = UserConfig.color(a, "accent", Color.parseColor("#4FD1FF"));
            String hint = UserConfig.jarvis(a).optString("hint", "Hold Back to talk");
            hud = new JarvisHud(a, persona(a), hint, accent);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(JarvisHud.WIDTH * k),
                    Math.round(JarvisHud.HEIGHT * k), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = Math.round(JarvisHud.TOP * k);
            a.addContentView(hud, lp);
            HUDS.put(a, hud);
            hud.resume();
        } else if (!on && hud != null) {
            hud.pause();
            if (hud.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) hud.getParent()).removeView(hud);
            HUDS.remove(a);
        }
    }

    static void onHomeResume(Activity a) {
        refresh(a);
        JarvisHud hud = HUDS.get(a);
        if (hud != null) hud.resume();
    }

    static void onHomePause(Activity a) {
        JarvisHud hud = HUDS.get(a);
        if (hud != null) hud.pause();
        holdingBack = false;
    }

    static void onHomeDestroy(Activity a) {
        JarvisHud hud = HUDS.remove(a);
        if (hud != null) hud.pause();
    }

    /** Wake keys, on the home screen only. True = the key is JARVIS's (the home screen doesn't see it). */
    static boolean onHomeKey(Activity a, KeyEvent e) {
        if (!UserConfig.jarvisEnabled(a)) { holdingBack = false; return false; }
        JSONObject wake = UserConfig.jarvis(a).optJSONObject("wake");
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK && (wake == null || wake.optBoolean("hold_back", true))) {
            if (holdingBack) {   // the rest of a hold that already woke JARVIS
                if (e.getAction() == KeyEvent.ACTION_UP) holdingBack = false;
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && (e.isLongPress() || e.getRepeatCount() >= 6)) {
                holdingBack = true;
                open(a, "hold back");
                return true;
            }
            return false;   // a short press stays Free My TV's
        }
        JSONArray keys = wake == null ? null : wake.optJSONArray("keys");
        if (keys != null) for (int i = 0; i < keys.length(); i++) {
            if (keys.optInt(i, -1) != code) continue;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) open(a, KeyEvent.keyCodeToString(code));
            return true;
        }
        return false;
    }

    private static void open(Activity a, String why) {
        AppLog.w("JARVIS: woken (" + why + ")");
        a.startActivity(new Intent(a, JarvisActivity.class));
    }

    /** The toggle (Settings row, Commands "jarvis"): saved in config.json; the home screen redraws on the change. */
    static void setEnabled(Context c, boolean on) {
        UserConfig.setJarvisEnabled(c, on);
        // The "JARVIS" entry in the TV's app list follows the toggle, so classic mode leaves no trace of it.
        try {
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, "io.freemytv.JarvisLauncher"),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            AppLog.w("JARVIS: app-list entry: " + e.getMessage());
        }
    }
}
