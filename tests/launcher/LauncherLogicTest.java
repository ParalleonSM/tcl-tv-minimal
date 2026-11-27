package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * JVM tests for the launcher's pure-Java logic (no TV, no emulator): the phrase matcher (Intents), JARVIS's words
 * (Persona) and the shipped config/config.json. Run by tests/run-launcher-tests.sh. No JUnit: a plain main().
 */
public class LauncherLogicTest {
    static int passed, failed;

    static void check(boolean ok, String what) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL  " + what); }
    }

    static void eq(Object got, Object want, String what) {
        check(want == null ? got == null : want.equals(got), what + " — got " + got + ", want " + want);
    }

    /** Built-in action types, as Commands registers them (+ JARVIS's). */
    static final Set<String> ACTIONS = new HashSet<>(Arrays.asList("launch_app", "hdmi", "live_tv", "home", "settings",
            "search", "volume", "media", "status", "jarvis", "macro", "speak", "listen", "voices", "utterance", "list", "reload_config"));

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        String text = new String(Files.readAllBytes(Paths.get(root, "config/config.json")), StandardCharsets.UTF_8);
        JSONObject cfg = new JSONObject(text);
        List<Intents.Spec> specs = Intents.specs(cfg.getJSONArray("commands"));
        List<String> wake = Arrays.asList("hey jarvis", "jarvis");

        // --- normalisation
        eq(Intents.normalize("Hey JARVIS, could you switch to HDMI two, please?", wake), "switch to hdmi 2", "normalize polite+wake+number");
        eq(Intents.normalize("Set the volume to twenty-five", null), "set the volume to 25", "normalize twenty-five");
        eq(Intents.normalize("volume thirty", null), "volume 30", "normalize thirty");
        eq(Intents.number("hdmi three"), 3, "number(hdmi three)");
        eq(Intents.number("-3"), -3, "number(-3)");
        eq(Intents.number("netflix"), -1, "number(no digits)");

        // --- utterance -> command id + args
        Object[][] cases = {
            {"Open Netflix", "netflix", null, null},
            {"netflix", "netflix", null, null},
            {"Jarvis, open Disney Plus please", "open_app", "app", "disney plus"},
            {"launch spotify", "open_app", "app", "spotify"},
            {"switch to HDMI two", "hdmi", "input", "2"},
            {"HDMI 3", "hdmi", "input", "3"},
            {"go to hdmi 1", "hdmi", "input", "1"},
            {"change input to three", "hdmi", "input", "3"},
            {"volume down three", "vol_down_n", "step", "3"},
            {"volume down three", "vol_down_n", "direction", "down"},
            {"turn it up", "vol_up", "step", "+2"},
            {"set the volume to twenty five", "vol_set", "level", "25"},
            {"mute", "mute", "mute", "true"},
            {"unmute", "unmute", "mute", "false"},
            {"search for the office bloopers", "search", "query", "the office bloopers"},
            {"search youtube for lofi beats", "search", "query", "lofi beats"},
            {"status report", "status", null, null},
            {"What's the time?", "status", null, null},
            {"pause", "play_pause", "key", "play_pause"},
            {"next episode", "next", "key", "next"},
            {"live TV", "live_tv", null, null},
            {"go home", "home", null, null},
            {"movie night", "movie_night", "run", "vol_12;netflix"},
            {"classic mode", "classic", "enabled", "false"},
        };
        for (Object[] c : cases) {
            Intents.Match m = Intents.match((String) c[0], specs, wake);
            if (m == null) { check(false, "\"" + c[0] + "\" matched nothing"); continue; }
            eq(m.spec.id, c[1], "\"" + c[0] + "\" -> id");
            if (c[2] != null) eq(m.args.get(c[2]), c[3], "\"" + c[0] + "\" -> " + c[2]);
        }
        check(Intents.match("the quick brown fox", specs, wake) == null, "nonsense matches nothing");
        check(Intents.match("", specs, wake) == null, "empty matches nothing");
        check(Intents.match("jarvis", specs, wake) == null, "wake word alone matches nothing");

        // --- every configured phrase routes to its own command (no phrase is shadowed by another)
        Map<String, String> sample = new HashMap<>();
        sample.put("app", "plex"); sample.put("input", "2"); sample.put("query", "cats"); sample.put("step", "3"); sample.put("level", "10");
        for (Intents.Spec s : specs) {
            for (String p : s.phrases) {
                String said = p;
                for (Map.Entry<String, String> e : sample.entrySet()) said = said.replace("{" + e.getKey() + "}", e.getValue());
                Intents.Match m = Intents.match(said, specs, wake);
                check(m != null && m.spec.action.equals(s.action), "phrase \"" + p + "\" (" + s.id + ") routes to "
                        + (m == null ? "nothing" : m.spec.id));
            }
        }

        // --- config.json is sound
        Set<String> ids = new HashSet<>();
        for (Intents.Spec s : specs) {
            check(ids.add(s.id), "unique id " + s.id);
            check(ACTIONS.contains(s.action), "known action type " + s.action + " (" + s.id + ")");
        }
        for (Intents.Spec s : specs) {
            if (!s.action.equals("macro")) continue;
            for (String id : s.args.get("run").split("[;,]")) check(ids.contains(id.trim()), "macro " + s.id + " runs existing id " + id);
        }
        JSONObject aliases = cfg.getJSONObject("aliases");
        for (String k : aliases.keySet()) {
            if (k.startsWith("_")) continue;
            check(aliases.getString(k).matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+"), "alias " + k + " is a package name");
        }
        JSONObject jarvis = cfg.getJSONObject("jarvis");
        check(jarvis.getBoolean("enabled"), "ships with JARVIS on");
        check(cfg.getJSONObject("theme").getString("mode").equals("dark"), "classic theme is dark");
        String top = cfg.getJSONObject("layout").getString("top_row");
        check(top.equals("none") || top.equals("picks"), "layout.top_row is picks|none");
        JSONArray hidden = cfg.getJSONObject("apps").getJSONArray("hidden");
        for (int i = 0; i < hidden.length(); i++) check(hidden.getString(i).contains("."), "hidden entry is a package");
        java.nio.file.Path asset = Paths.get(root, "launcher/app/assets/config.default.json");
        if (Files.exists(asset)) {
            eq(new String(Files.readAllBytes(asset), StandardCharsets.UTF_8), text, "launcher asset == config/config.json (build.sh copies it)");
        }

        // --- Persona
        Persona sir = new Persona("JARVIS", "sir", new Random(1));
        eq(sir.greeting(9), "Good morning, sir.", "greeting morning");
        eq(sir.greeting(15), "Good afternoon, sir.", "greeting afternoon");
        eq(sir.greeting(22), "Good evening, sir.", "greeting evening");
        Persona plain = new Persona("JARVIS", "", new Random(1));
        eq(plain.greeting(9), "Good morning.", "greeting without address");
        Map<String, String> f = new HashMap<>();
        f.put("app", "Netflix");
        String d = sir.done("launch_app", f);
        check(d.contains("Netflix") && d.contains("sir"), "launch reply names the app and says sir: " + d);
        f.clear();
        f.put("time", "9:41 PM"); f.put("online", "true"); f.put("network", "Wi-Fi"); f.put("volume", "12"); f.put("apps", "23");
        String st = sir.status(f);
        check(st.contains("9:41 PM") && st.contains("Wi-Fi") && st.contains("Volume at 12") && st.contains("sir"), "status report: " + st);
        f.put("did", "mute");
        check(sir.done("volume", f).toLowerCase().contains("mute") || sir.done("volume", f).contains("Silence"), "mute reply");
        check(sir.didNotCatch("flibbertigibbet").contains("sir"), "didNotCatch says sir");
        check(!plain.done("home", new HashMap<String, String>()).contains(", ."), "no dangling comma without address");
        for (int i = 0; i < 50; i++) check(!sir.listening().isEmpty(), "listening line");

        System.out.println("launcher logic: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
