package io.freemytv;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The command layer: a registry of named actions that voice (JARVIS), the on-screen JARVIS mode and an external agent
 * (scripts/tvctl.sh, over adb: the Control provider) all drive the same way.
 *
 * A command is JSON: {"action": "<name>", ...args}. Built-in action types are registered below (ACTIONS); config.json's
 * "commands" maps phrases onto them with fixed args ("open netflix" -> launch_app app=com.netflix.ninja), and its
 * "macro" type chains other commands by id, so new commands need no rebuild. A new action TYPE is one register() call.
 *
 * Every reply is JSON: {"ok": true|false, "action": "...", "say": "<a plain sentence>", ...facts}. In JARVIS mode the
 * voice layer turns the facts into JARVIS's words (Persona); "say" stays plain so an agent can read it.
 */
final class Commands {
    private Commands() {}

    interface Action {
        /** Fills `reply` (ok, say, facts). Runs on a binder thread or the main thread; must not block for long. */
        void run(Context c, JSONObject args, JSONObject reply) throws Exception;
    }

    private static final Map<String, Action> ACTIONS = new LinkedHashMap<>();
    private static final Map<String, String> HELP = new LinkedHashMap<>();

    static synchronized void register(String name, String help, Action a) {
        ACTIONS.put(name, a);
        HELP.put(name, help);
    }

    static {
        register("launch_app", "Open an app. app=<package, alias or name>", Commands::launchApp);
        register("hdmi", "Switch to an HDMI input. input=<number or name>", Commands::hdmi);
        register("live_tv", "Open live TV (the antenna's channels)", (c, a, r) -> {
            boolean ok = HomeApps.open(c, HomeApps.action(HomeApps.LIVE_TV, HomeApps.TOP_LIVE, "Live TV", "live_tv"), null);
            r.put("ok", ok).put("say", ok ? "Live TV." : "Couldn't open live TV.");
        });
        register("home", "Go to the home screen", (c, a, r) -> {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            r.put("ok", true).put("say", "Home.");
        });
        register("settings", "Open TV settings", (c, a, r) -> {
            boolean ok = HomeApps.open(c, HomeApps.tvSettings(), null);
            r.put("ok", ok).put("say", ok ? "Settings." : "Couldn't open settings.");
        });
        register("search", "Search. query=<text> [package=<app that searches, default YouTube>]", Commands::search);
        register("volume", "Volume. step=<+n/-n> [direction=up|down] | level=<0..max> | mute=true|false", Commands::volume);
        register("media", "Playback. key=play_pause|play|pause|next|previous|stop|rewind|fast_forward", Commands::media);
        register("status", "Status report (time, network, volume, apps, mode)", Commands::status);
        register("jarvis", "The JARVIS layout toggle. enabled=true|false (saved in config.json)", (c, a, r) -> {
            if (!JarvisHooks.AVAILABLE) { r.put("ok", false).put("say", "This is the classic build: no JARVIS in it."); return; }
            boolean on = bool(a, "enabled", !UserConfig.jarvisEnabled(c));
            JarvisHooks.setEnabled(c, on);
            r.put("ok", true).put("enabled", String.valueOf(on)).put("say", on ? "JARVIS layout on." : "Classic layout on.");
        });
        register("macro", "Run other commands by id, in order. run=<id;id;…> [delay_ms=<between, default 800>]", Commands::macro);
        JarvisHooks.registerActions();   // speak, listen, voices (jarvis build only)
    }

    // MARK: - Entry points

    /** Runs one JSON command (from Control, JARVIS or the tests). Never throws: errors come back as ok=false. */
    static JSONObject run(Context c, JSONObject cmd) {
        JSONObject reply = new JSONObject();
        String action = cmd.optString("action", "");
        try {
            reply.put("action", action);
            switch (action) {
                case "list": list(c, reply); break;
                case "reload_config": reloadConfig(c, reply); break;
                case "utterance": utterance(c, cmd.optString("text", ""), reply); break;
                default: {
                    Action a;
                    synchronized (Commands.class) { a = ACTIONS.get(action); }
                    if (a == null) {
                        reply.put("ok", false).put("say", "No such action: " + action + ". Try {\"action\":\"list\"}.");
                    } else {
                        a.run(c, cmd, reply);
                    }
                }
            }
        } catch (Exception e) {
            try {
                reply.put("ok", false).put("say", "Error: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            } catch (JSONException ignored) {
            }
            AppLog.w("command " + action + ": " + e);
        }
        if (!reply.has("ok")) try { reply.put("ok", true); } catch (JSONException ignored) { }
        return reply;
    }

    /** Text (spoken, or typed by an agent) -> the registry -> the action. reply.heard / reply.matched say what happened. */
    static void utterance(Context c, String text, JSONObject reply) throws JSONException {
        reply.put("heard", text);
        List<Intents.Spec> specs = UserConfig.specs(c);
        Intents.Match m = Intents.match(text, specs, UserConfig.wakeWords(c));
        if (m == null) {
            reply.put("ok", false).put("matched", JSONObject.NULL).put("say", "No command matches \"" + text + "\".");
            return;
        }
        JSONObject cmd = new JSONObject();
        for (Map.Entry<String, String> e : m.args.entrySet()) cmd.put(e.getKey(), e.getValue());
        cmd.put("action", m.spec.action);
        JSONObject inner = run(c, cmd);
        for (Iterator<String> it = inner.keys(); it.hasNext(); ) {
            String k = it.next();
            reply.put(k, inner.get(k));
        }
        reply.put("matched", m.spec.id).put("phrase", m.phrase).put("action", m.spec.action);
    }

    private static void list(Context c, JSONObject reply) throws JSONException {
        JSONObject types = new JSONObject();
        synchronized (Commands.class) { for (Map.Entry<String, String> e : HELP.entrySet()) types.put(e.getKey(), e.getValue()); }
        types.put("utterance", "Match text against the phrases below and run it. text=<what was said>");
        types.put("list", "This list");
        types.put("reload_config", "Read config.json again");
        JSONArray commands = new JSONArray();
        for (Intents.Spec s : UserConfig.specs(c)) {
            JSONObject o = new JSONObject().put("id", s.id).put("action", s.action).put("phrases", new JSONArray(s.phrases));
            if (!s.args.isEmpty()) o.put("args", new JSONObject(s.args));
            commands.put(o);
        }
        reply.put("ok", true).put("actions", types).put("commands", commands)
                .put("say", types.length() + " action types, " + commands.length() + " commands.");
    }

    private static void reloadConfig(Context c, JSONObject reply) throws JSONException {
        JSONObject cfg = UserConfig.get(c);
        String err = UserConfig.lastError();
        reply.put("ok", err.isEmpty()).put("config", err.isEmpty() ? "loaded" : err)
                .put("jarvis", UserConfig.jarvisEnabled(c)).put("commands", UserConfig.specs(c).size())
                .put("schema", cfg.optInt("schema", 0))
                .put("say", err.isEmpty() ? "Config loaded." : "Config problem: " + err);
    }

    // MARK: - Actions

    /** Package, alias (config "aliases"), or the app's name as the TV shows it ("Prime Video", "prime"). */
    static String resolveApp(Context c, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        PackageManager pm = c.getPackageManager();
        String n = name.trim();
        if (n.matches("[A-Za-z_][\\w]*(\\.[\\w]+)+") && HomeApps.launchIntent(pm, n) != null) return n;
        String alias = UserConfig.alias(c, n);
        if (alias != null && HomeApps.launchIntent(pm, alias) != null) return alias;
        String want = Intents.normalize(n, null).replace(" ", "");
        String partial = null;
        for (String[] app : HomeApps.launchable(c)) {
            String label = Intents.normalize(app[1], null).replace(" ", "");
            if (label.isEmpty()) continue;
            if (label.equals(want)) return app[0];
            if (partial == null && (label.startsWith(want) || want.startsWith(label) || label.contains(want))) partial = app[0];
        }
        return partial;
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static void launchApp(Context c, JSONObject a, JSONObject r) throws JSONException {
        String asked = a.optString("app", "");
        String pkg = resolveApp(c, asked);
        if (pkg == null) {
            r.put("ok", false).put("app", asked).put("say", "No app called \"" + asked + "\" on this TV.");
            return;
        }
        Intent i = HomeApps.launchIntent(c.getPackageManager(), pkg);
        if (i == null) { r.put("ok", false).put("say", pkg + " can't be opened."); return; }
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Recent.note(c, pkg);
        String name = label(c, pkg);
        r.put("ok", true).put("app", name).put("package", pkg).put("say", "Opening " + name + ".");
    }

    private static void hdmi(Context c, JSONObject a, JSONObject r) throws JSONException {
        String asked = a.optString("input", "");
        List<HomeApps.Item> inputs = HomeApps.inputs(c, true);
        int n = Intents.number(asked);
        HomeApps.Item pick = null;
        for (HomeApps.Item it : inputs) {
            if (n > 0 && String.valueOf(n).equals(it.number)) { pick = it; break; }
            if (n <= 0 && !asked.isEmpty() && Intents.normalize(it.label, null).contains(Intents.normalize(asked, null))) { pick = it; break; }
        }
        JSONArray all = new JSONArray();
        for (HomeApps.Item it : inputs) all.put(new JSONObject().put("number", it.number).put("label", it.label).put("connected", it.connected));
        r.put("inputs", all);
        if (pick == null) {
            r.put("ok", false).put("say", "No HDMI input \"" + asked + "\". This TV has " + inputs.size() + ".");
            return;
        }
        boolean ok = HomeApps.open(c, pick, null);
        r.put("ok", ok).put("input", pick.label).put("number", pick.number)
                .put("say", ok ? "Switching to " + pick.label + "." : "Couldn't open " + pick.label + ".");
    }

    private static void search(Context c, JSONObject a, JSONObject r) throws JSONException {
        String q = a.optString("query", "").trim();
        if (q.isEmpty()) { r.put("ok", false).put("say", "Search for what?"); return; }
        String pkg = a.optString("package", "");
        if (pkg.isEmpty()) pkg = UserConfig.get(c).optJSONObject("search") == null ? "com.google.android.youtube.tv"
                : UserConfig.get(c).optJSONObject("search").optString("package", "com.google.android.youtube.tv");
        PackageManager pm = c.getPackageManager();
        Intent[] tries = {
            new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q),
            new Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, q),
            new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q),
        };
        for (Intent i : tries) {
            ComponentName target = i.resolveActivity(pm);
            if (target == null) continue;
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            r.put("ok", true).put("query", q).put("in", target.getPackageName()).put("say", "Searching for " + q + ".");
            return;
        }
        r.put("ok", false).put("query", q).put("say", "Nothing on this TV takes a search.");
    }

    private static void volume(Context c, JSONObject a, JSONObject r) throws JSONException {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null) { r.put("ok", false).put("say", "No audio service."); return; }
        int s = AudioManager.STREAM_MUSIC, max = am.getStreamMaxVolume(s);
        String did = "level";
        if (a.has("mute")) {
            boolean mute = bool(a, "mute", true);
            am.adjustStreamVolume(s, mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
            did = mute ? "mute" : "unmute";
        } else if (a.has("level")) {
            int lvl = Intents.number(a.optString("level"));
            if (lvl < 0) { r.put("ok", false).put("say", "Volume to what?"); return; }
            am.setStreamVolume(s, Math.max(0, Math.min(max, lvl)), AudioManager.FLAG_SHOW_UI);
        } else {
            // step: "+3", "-2", "3" with direction=down, "up", "down"; at most 20 steps a time.
            String st = a.optString("step", "+1").trim().toLowerCase(Locale.ROOT);
            int n = Intents.number(st.replace("+", "").replace("-", ""));
            if (n <= 0) n = 1;
            boolean down = st.startsWith("-") || st.startsWith("down") || "down".equals(a.optString("direction", ""));
            int step = Math.min(20, n) * (down ? -1 : 1);
            for (int i = 0; i < Math.abs(step); i++) {
                am.adjustStreamVolume(s, step > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, i == 0 ? AudioManager.FLAG_SHOW_UI : 0);
            }
        }
        int level = am.getStreamVolume(s);
        r.put("ok", true).put("did", did).put("level", level).put("max", max).put("muted", am.isStreamMute(s))
                .put("say", did.equals("mute") ? "Muted." : did.equals("unmute") ? "Unmuted." : "Volume " + level + " of " + max + ".");
    }

    private static final Map<String, Integer> MEDIA_KEYS = new HashMap<>();
    static {
        MEDIA_KEYS.put("play_pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        MEDIA_KEYS.put("play", KeyEvent.KEYCODE_MEDIA_PLAY);
        MEDIA_KEYS.put("pause", KeyEvent.KEYCODE_MEDIA_PAUSE);
        MEDIA_KEYS.put("next", KeyEvent.KEYCODE_MEDIA_NEXT);
        MEDIA_KEYS.put("previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        MEDIA_KEYS.put("stop", KeyEvent.KEYCODE_MEDIA_STOP);
        MEDIA_KEYS.put("rewind", KeyEvent.KEYCODE_MEDIA_REWIND);
        MEDIA_KEYS.put("fast_forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
    }

    private static void media(Context c, JSONObject a, JSONObject r) throws JSONException {
        String key = a.optString("key", "play_pause").toLowerCase(Locale.ROOT).replace(' ', '_');
        Integer code = MEDIA_KEYS.get(key);
        AudioManager am = c.getSystemService(AudioManager.class);
        if (code == null || am == null) { r.put("ok", false).put("say", "Unknown media key " + key + "."); return; }
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        String pretty = key.replace('_', ' ');
        r.put("ok", true).put("key", Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1)).put("say", pretty + ".");
    }

    private static void status(Context c, JSONObject a, JSONObject r) throws JSONException {
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        r.put("ok", true).put("time", time);
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        String network = null;
        boolean online = false;
        if (cm != null) {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps != null) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                network = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : "the network";
            }
        }
        r.put("online", String.valueOf(online));
        if (network != null) r.put("network", network);
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) {
            r.put("volume", String.valueOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)))
                    .put("volume_max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    .put("muted", String.valueOf(am.isStreamMute(AudioManager.STREAM_MUSIC)));
        }
        r.put("apps", String.valueOf(HomeApps.launchable(c).size()));
        r.put("mode", HomeMode.state(c).optString("mode", HomeMode.ORIGINAL));
        r.put("jarvis", UserConfig.jarvisEnabled(c)).put("jarvis_build", JarvisHooks.AVAILABLE);
        r.put("model", Build.MANUFACTURER + " " + Build.MODEL).put("android", Build.VERSION.RELEASE);
        r.put("uptime_min", SystemClock.elapsedRealtime() / 60_000);
        String version;
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "?";
        }
        r.put("launcher_version", version);
        r.put("say", "It's " + time + ". " + (online ? "Online" + (network != null ? " over " + network : "") : "Offline")
                + (am != null ? ". Volume " + am.getStreamVolume(AudioManager.STREAM_MUSIC) : "") + ".");
    }

    private static void macro(Context c, JSONObject a, JSONObject r) throws Exception {
        String[] ids = a.optString("run", "").split("[;,]");
        long delay = Math.max(0, Math.min(5000, a.optLong("delay_ms", 800)));
        Map<String, Intents.Spec> byId = new HashMap<>();
        for (Intents.Spec s : UserConfig.specs(c)) byId.put(s.id, s);
        JSONArray steps = new JSONArray();
        boolean all = true;
        int depth = a.optInt("_depth", 0);
        if (depth > 3) { r.put("ok", false).put("say", "Macros nest too deep."); return; }
        for (String raw : ids) {
            String id = raw.trim();
            if (id.isEmpty()) continue;
            Intents.Spec s = byId.get(id);
            if (s == null) { steps.put(new JSONObject().put("id", id).put("ok", false)); all = false; continue; }
            JSONObject cmd = new JSONObject(new HashMap<String, Object>(s.args)).put("action", s.action).put("_depth", depth + 1);
            JSONObject res = run(c, cmd);
            steps.put(new JSONObject().put("id", id).put("ok", res.optBoolean("ok")).put("say", res.optString("say")));
            all &= res.optBoolean("ok");
            if (delay > 0) Thread.sleep(delay);
        }
        r.put("ok", all).put("steps", steps).put("say", all ? "Done." : "Some steps failed.");
    }

    // MARK: - Helpers

    static boolean bool(JSONObject a, String key, boolean fallback) {
        if (!a.has(key)) return fallback;
        String v = a.optString(key, "").toLowerCase(Locale.ROOT).trim();
        return v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("1") || (!v.equals("false") && !v.equals("off")
                && !v.equals("no") && !v.equals("0") && fallback);
    }

    /** "facts" for Persona: the reply's string values. */
    static Map<String, String> facts(JSONObject reply) {
        Map<String, String> out = new HashMap<>();
        for (Iterator<String> it = reply.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = reply.opt(k);
            if (v instanceof String || v instanceof Number || v instanceof Boolean) out.put(k, String.valueOf(v));
        }
        return out;
    }
}
