package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * config.json: the user-editable file that drives the look, the layout, the apps, JARVIS and the command registry —
 * read at launch, no rebuild needed to change any of it.
 *
 * Where it lives: the app's private files (files/config.json), written by scripts/push-config.sh through the Control
 * provider (only adb's shell may), and by the launcher itself when the JARVIS toggle changes (so the toggle's state
 * persists in the file). Without one, assets/config.default.json (a copy of the repo's config/config.json, made by
 * build.sh). The user file is merged over the defaults, so it may hold only the keys it changes.
 *
 * Layout and theme keys that Free My TV already has as on-TV settings (dark theme, top row, weather, shelf order…) are
 * written into Prefs only when those config sections change (configHash): what's changed on the TV afterwards sticks
 * until the next push. Toggling JARVIS doesn't count as a change there.
 */
final class UserConfig {
    private UserConfig() {}

    static final String FILE = "config.json", ASSET = "config.default.json";

    private static JSONObject merged, defaults;
    private static long loadedStamp = Long.MIN_VALUE;
    private static String lastError = "";
    private static volatile Set<String> hidden = Collections.emptySet();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** The merged config (defaults + user file), reloaded when the file changed on disk. Never null. */
    static synchronized JSONObject get(Context c) {
        File f = file(c);
        long stamp = f.isFile() ? f.lastModified() ^ f.length() : -1;
        if (merged == null || stamp != loadedStamp) {
            loadedStamp = stamp;
            JSONObject user = new JSONObject();
            if (f.isFile()) {
                try {
                    user = new JSONObject(read(f));
                    lastError = "";
                } catch (IOException | JSONException e) {
                    lastError = "config.json: " + e.getMessage();
                    AppLog.w(lastError + " (using the defaults)");
                }
            }
            merged = merge(copy(defaults(c)), user);
            hidden = stringSet(merged.optJSONObject("apps"), "hidden");
        }
        return merged;
    }

    static synchronized String lastError() {
        return lastError;
    }

    static synchronized JSONObject defaults(Context c) {
        if (defaults == null) {
            try (InputStream in = c.getAssets().open(ASSET)) {
                defaults = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                AppLog.w("config.default.json: " + e.getMessage());
                defaults = new JSONObject();
            }
        }
        return defaults;
    }

    /** The user's file as written (not merged), or {} — what the JARVIS toggle edits, so the rest stays as they wrote it. */
    static JSONObject user(Context c) {
        try {
            File f = file(c);
            return f.isFile() ? new JSONObject(read(f)) : new JSONObject();
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    /** A new config from the computer: null when taken, else why not (the old one stays). */
    static String accept(Context c, byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        try {
            new JSONObject(text);
        } catch (JSONException e) {
            return "not valid JSON: " + e.getMessage();
        }
        try {
            write(c, text);
        } catch (IOException e) {
            return "couldn't save it: " + e.getMessage();
        }
        changed(c);
        return null;
    }

    /** The JARVIS toggle: saved in config.json itself (jarvis.enabled), so the file is the one place it lives. */
    static void setJarvisEnabled(Context c, boolean on) {
        JSONObject user = user(c);
        try {
            JSONObject j = user.optJSONObject("jarvis");
            if (j == null) user.put("jarvis", j = new JSONObject());
            j.put("enabled", on);
            write(c, user.toString(2));
        } catch (JSONException | IOException e) {
            AppLog.w("couldn't save the JARVIS toggle: " + e.getMessage());
        }
        changed(c);
    }

    static boolean jarvisEnabled(Context c) {
        JSONObject j = get(c).optJSONObject("jarvis");
        return JarvisHooks.AVAILABLE && (j == null || j.optBoolean("enabled", true));
    }

    static JSONObject jarvis(Context c) {
        JSONObject j = get(c).optJSONObject("jarvis");
        return j == null ? new JSONObject() : j;
    }

    // MARK: - Theme (read once per home screen; a change recreates it)

    /** theme.<key> as a colour; while JARVIS is on, jarvis.theme.<key> wins (off: the classic theme, exactly). */
    static int color(Context c, String key, int fallback) {
        JSONObject t = get(c).optJSONObject("theme");
        String v = t == null ? "" : t.optString(key, "");
        JSONObject jt = jarvisEnabled(c) ? jarvis(c).optJSONObject("theme") : null;
        if (jt != null && !jt.optString(key, "").isEmpty()) v = jt.optString(key);
        if (v.isEmpty()) return fallback;
        try {
            return Color.parseColor(v);
        } catch (IllegalArgumentException e) {
            AppLog.w("theme." + key + ": not a color (" + v + ")");
            return fallback;
        }
    }

    static float number(Context c, String section, String key, float fallback, float min, float max) {
        JSONObject t = get(c).optJSONObject(section);
        if (t == null || !t.has(key)) return fallback;
        double v = t.optDouble(key, fallback);
        return Double.isNaN(v) ? fallback : (float) Math.max(min, Math.min(max, v));
    }

    // MARK: - Apps

    /** Apps the user hid (apps.hidden): left out of the shelf and All Apps, like HomeApps.HIDDEN. */
    static boolean hidden(String pkg) {
        return hidden.contains(pkg);
    }

    // MARK: - Commands

    /** The command registry: built-ins (Commands.BUILT_IN phrases in the defaults) plus the user's, as Intents specs. */
    static List<Intents.Spec> specs(Context c) {
        return Intents.specs(get(c).optJSONArray("commands"));
    }

    static List<String> wakeWords(Context c) {
        List<String> out = new ArrayList<>();
        JSONArray a = jarvis(c).optJSONArray("wake_words");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        if (out.isEmpty()) { out.add("hey jarvis"); out.add("jarvis"); }
        return out;
    }

    /** "netflix" -> "com.netflix.ninja" from config aliases; null when not there. */
    static String alias(Context c, String name) {
        JSONObject a = get(c).optJSONObject("aliases");
        if (a == null || name == null) return null;
        String v = a.optString(name.toLowerCase(java.util.Locale.ROOT).trim(), "");
        return v.isEmpty() ? null : v;
    }

    // MARK: - Into Free My TV's own settings, when they changed in the file

    private static final String[] PREF_SECTIONS = {"theme", "layout", "apps"};

    /** Writes the layout/theme/app keys into Prefs if those sections changed since the last time. Call on :home's main thread. */
    static void applyToPrefs(Context c) {
        JSONObject cfg = get(c);
        StringBuilder sig = new StringBuilder();
        for (String s : PREF_SECTIONS) sig.append(s).append('=').append(String.valueOf(cfg.opt(s))).append(';');
        String hash = Integer.toHexString(sig.toString().hashCode());
        SharedPreferences sp = c.getSharedPreferences("userconfig", Context.MODE_PRIVATE);
        if (hash.equals(sp.getString("applied", ""))) return;
        JSONObject theme = cfg.optJSONObject("theme"), layout = cfg.optJSONObject("layout"), apps = cfg.optJSONObject("apps");
        if (theme != null) {
            String mode = theme.optString("mode", "");
            if (mode.equals("dark") || mode.equals("light") || mode.equals("auto")) Prefs.setTheme(mode);
            String clock = theme.optString("clock", "");
            if (clock.equals("auto") || clock.equals("12") || clock.equals("24")) Prefs.setClockFormat(clock);
        }
        if (layout != null) {
            String top = layout.optString("top_row", "");
            // "continue" needs a permission granted through Home Settings, so the file offers picks or none only.
            if (top.equals(Prefs.TOP_PICKS) || top.equals(Prefs.TOP_NONE)) Prefs.setTopContent(top);
            if (layout.has("picks_count")) Prefs.setCards(Math.max(1, Math.min(6, layout.optInt("picks_count", 3))));
            if (layout.has("weather")) Prefs.setWeather(layout.optBoolean("weather", true));
            if (layout.has("markets")) Prefs.setQuotes(layout.optBoolean("markets", false));
            if (layout.has("photos")) Prefs.setPhotos(layout.optBoolean("photos", false));
            if (layout.has("status_icons")) Prefs.setStatusIcons(layout.optBoolean("status_icons", false));
            List<String> buttons = list(layout, "top_buttons");
            if (!buttons.isEmpty()) Prefs.setTopButtons(buttons);
        }
        if (apps != null) {
            List<String> pinned = list(apps, "pinned");
            if (!pinned.isEmpty()) Prefs.setShelf(pinned);
        }
        sp.edit().putString("applied", hash).apply();
        AppLog.w("config.json applied to the home screen settings (" + hash + ")");
    }

    // MARK: - Change notices

    static void listen(Runnable r) { LISTENERS.add(r); }
    static void unlisten(Runnable r) { LISTENERS.remove(r); }

    private static void changed(Context c) {
        synchronized (UserConfig.class) { loadedStamp = Long.MIN_VALUE; }
        get(c);
        new Handler(Looper.getMainLooper()).post(() -> {
            applyToPrefs(c);
            for (Runnable r : LISTENERS) r.run();
        });
    }

    // MARK: - Helpers

    private static void write(Context c, String text) throws IOException {
        File f = file(c), part = new File(c.getFilesDir(), "." + FILE);
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!part.renameTo(f)) throw new IOException("rename failed");
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** `over`'s keys into `base`: objects merge key by key, anything else (arrays too) replaces. Keys starting "_" are notes. */
    static JSONObject merge(JSONObject base, JSONObject over) {
        for (Iterator<String> it = over.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = over.opt(k);
            try {
                if (v instanceof JSONObject && base.optJSONObject(k) != null) merge(base.getJSONObject(k), (JSONObject) v);
                else base.put(k, v);
            } catch (JSONException ignored) {
            }
        }
        return base;
    }

    static List<String> list(JSONObject o, String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = o == null ? null : o.optJSONArray(key);
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static Set<String> stringSet(JSONObject o, String key) {
        return new HashSet<>(list(o, key));
    }
}
