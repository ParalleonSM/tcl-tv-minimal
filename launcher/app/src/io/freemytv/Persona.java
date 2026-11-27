package io.freemytv;

import java.util.Calendar;
import java.util.Map;
import java.util.Random;

/**
 * JARVIS's way of putting things: polite, brief, addresses the user ("sir" by default, config jarvis.address), a status
 * report on request, a little dry wit. Plain Java (JVM-tested). Every reply is a template with {placeholders}; the
 * classic layout never calls this (its command replies are plain facts).
 *
 * Honest limit: this is templates and a phrase matcher, not a language model. Open-ended conversation is the companion
 * agent's job (see NIGHT_REPORT.md, "Companion agent"), which can drive the same command layer and speak through it.
 */
final class Persona {
    final String name, address;
    private final Random random;

    Persona(String name, String address, Random random) {
        this.name = name == null || name.isEmpty() ? "JARVIS" : name;
        this.address = address == null ? "sir" : address.trim();
        this.random = random == null ? new Random() : random;
    }

    private String pick(String... options) {
        return options[random.nextInt(options.length)];
    }

    /** ", sir" (or nothing when the address is empty). */
    private String sir() {
        return address.isEmpty() ? "" : ", " + address;
    }

    private static String fill(String template, Map<String, String> values) {
        String out = template;
        if (values != null) for (Map.Entry<String, String> e : values.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue());
        return out;
    }

    static String partOfDay(int hour) {
        return hour < 5 ? "evening" : hour < 12 ? "morning" : hour < 18 ? "afternoon" : "evening";
    }

    String greeting(int hour) {
        return "Good " + partOfDay(hour) + sir() + ".";
    }

    String greeting() {
        return greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
    }

    /** What JARVIS says when the voice mode opens. */
    String listening() {
        return pick("Yes" + sir() + "?", "At your service" + sir() + ".", "Listening" + sir() + ".", "How may I help" + sir() + "?");
    }

    String didNotCatch(String heard) {
        if (heard == null || heard.trim().isEmpty()) {
            return pick("I didn't catch that" + sir() + ".", "Nothing came through" + sir() + ". Try once more?");
        }
        return pick("I'm afraid \"" + heard + "\" isn't in my repertoire yet" + sir() + ". Try \"open Netflix\" or \"switch to HDMI 2\".",
                "I didn't quite follow" + sir() + ". \"Status report\" or \"open YouTube\" are safe bets.",
                "Regrettably, \"" + heard + "\" escapes me" + sir() + ".");
    }

    String failed(String what) {
        return pick("I'm afraid " + what + sir() + ".", "That didn't go to plan" + sir() + ": " + what + ".");
    }

    String noSpeech() {
        return "Speech recognition isn't available on this TV" + sir() + ". You may type instead.";
    }

    String toggledOff() {
        return "Very good" + sir() + ". Returning to the classic layout. Hold Back on the home screen, or use Settings, to bring me back.";
    }

    String toggledOn() {
        return greeting() + " " + name + " online. All systems nominal.";
    }

    /** The spoken reply for an action that succeeded. `facts` holds what the action reported (app, input, level…). */
    String done(String action, Map<String, String> facts) {
        switch (action == null ? "" : action) {
            case "launch_app":
                return fill(pick("Opening {app}" + sir() + ".", "{app}, coming right up" + sir() + ".", "Right away" + sir() + ". {app}."), facts);
            case "hdmi":
                return fill(pick("Switching to {input}" + sir() + ".", "{input}, as you wish" + sir() + "."), facts);
            case "live_tv":
                return pick("Live TV" + sir() + ".", "Back to the broadcast" + sir() + ".");
            case "home":
                return pick("Home" + sir() + ".", "As you were" + sir() + ".");
            case "search":
                return fill(pick("Searching for {query}" + sir() + ".", "Looking up {query}" + sir() + "."), facts);
            case "volume":
                if ("mute".equals(facts.get("did"))) return pick("Muted" + sir() + ".", "Silence" + sir() + ". Blissful.");
                if ("unmute".equals(facts.get("did"))) return "Sound restored" + sir() + ".";
                return fill("Volume at {level}" + sir() + ".", facts);
            case "media":
                return fill(pick("{key}" + sir() + ".", "Done" + sir() + "."), facts);
            case "settings":
                return "Settings" + sir() + ".";
            case "status":
                return status(facts);
            case "jarvis":
                return "true".equals(facts.get("enabled")) ? toggledOn() : toggledOff();
            default:
                return pick("Done" + sir() + ".", "Consider it done" + sir() + ".");
        }
    }

    /** A spoken status report from the facts Commands.status collected. */
    String status(Map<String, String> f) {
        StringBuilder s = new StringBuilder();
        s.append(pick("All systems nominal" + sir() + ". ", "Status report" + sir() + ". ", "Everything is in order" + sir() + ". "));
        if (f.containsKey("time")) s.append("It's ").append(f.get("time")).append(". ");
        if ("true".equals(f.get("online"))) s.append("We're online");
        else if ("false".equals(f.get("online"))) s.append("We appear to be offline");
        if (f.containsKey("network")) s.append(" over ").append(f.get("network"));
        if (f.containsKey("online")) s.append(". ");
        if (f.containsKey("volume")) {
            s.append("Volume at ").append(f.get("volume"));
            if ("true".equals(f.get("muted"))) s.append(", muted");
            s.append(". ");
        }
        if (f.containsKey("apps")) s.append(f.get("apps")).append(" apps at your disposal. ");
        if ("0".equals(f.get("volume")) || "true".equals(f.get("muted"))) s.append("Rather quiet in here, if I may say.");
        return s.toString().trim();
    }
}
