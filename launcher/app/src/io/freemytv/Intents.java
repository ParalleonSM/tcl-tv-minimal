package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what was said (or typed by an agent) into one entry of the command registry. Plain Java, no Android: it runs in
 * the JVM tests (tests/launcher) as it runs on the TV.
 *
 * A command is {id, action, args, phrases}. A phrase is words with optional {slots}: "switch to hdmi {input}",
 * "open {app}". Matching: the text is normalised (lower case, no punctuation, number words to digits, polite filler and
 * the wake word removed), then every phrase is tried as a whole-text pattern; the most specific match wins (most literal
 * words, then fewest slots). Slot values come back raw ("netflix", "2"): resolving an app name to a package is the
 * caller's job (Commands), since it needs the TV's app list.
 */
final class Intents {
    private Intents() {}

    static final class Spec {
        final String id, action;
        final Map<String, String> args;
        final List<String> phrases;

        Spec(String id, String action, Map<String, String> args, List<String> phrases) {
            this.id = id;
            this.action = action;
            this.args = args == null ? new HashMap<String, String>() : args;
            this.phrases = phrases == null ? new ArrayList<String>() : phrases;
        }
    }

    static final class Match {
        final Spec spec;
        /** The spec's own args, then the slots filled from the text (slots win). */
        final Map<String, String> args;
        final String phrase;

        Match(Spec spec, Map<String, String> args, String phrase) {
            this.spec = spec;
            this.args = args;
            this.phrase = phrase;
        }
    }

    private static final String[] NUMBERS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen", "twenty"};
    private static final Map<String, String> TENS = new HashMap<>();
    static {
        TENS.put("thirty", "30"); TENS.put("forty", "40"); TENS.put("fifty", "50"); TENS.put("sixty", "60");
        TENS.put("seventy", "70"); TENS.put("eighty", "80"); TENS.put("ninety", "90"); TENS.put("hundred", "100");
    }
    /** Said around a command, never part of one. Longest first. */
    private static final String[] FILLER = {"would you kindly", "would you please", "could you please", "can you please",
            "would you", "could you", "can you", "will you", "i'd like you to", "i want you to", "i want to", "id like to",
            "please", "for me", "now", "thanks", "thank you", "kindly", "just", "go ahead and", "hey", "ok", "okay"};

    /** Lower case, words only, number words as digits, filler and the wake words dropped, single spaces. */
    static String normalize(String text, List<String> wakeWords) {
        if (text == null) return "";
        String s = " " + text.toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("[^a-z0-9' +-]", " ").replaceAll("(?<=[a-z])-(?=[a-z])", " ")
                .replaceAll("\\s+", " ").trim() + " ";
        // Filler first ("ok jarvis", "hey jarvis"), then the wake word, only where a sentence starts with it:
        // "turn off jarvis" keeps its "jarvis".
        for (String f : FILLER) s = s.replace(" " + f + " ", " ");
        if (wakeWords != null) {
            List<String> ws = new ArrayList<>(wakeWords);
            Collections.sort(ws, (a, b) -> b.length() - a.length());
            for (String w : ws) {
                String ww = " " + w.toLowerCase(Locale.ROOT).trim() + " ";
                if (ww.trim().isEmpty()) continue;
                while (s.startsWith(ww)) s = s.substring(ww.length() - 1);
                for (String f : FILLER) s = s.replace(" " + f + " ", " ");
            }
        }
        // "twenty five" -> "25", "two" -> "2", "fifty" -> "50"
        String[] words = s.trim().isEmpty() ? new String[0] : s.trim().split(" ");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (TENS.containsKey(w)) {
                int v = Integer.parseInt(TENS.get(w));
                int unit = i + 1 < words.length ? index(words[i + 1]) : -1;
                if (unit > 0 && unit < 10 && v < 100) { out.add(String.valueOf(v + unit)); i++; continue; }
                out.add(String.valueOf(v));
                continue;
            }
            int n = index(w);
            if (n == 20 && i + 1 < words.length && index(words[i + 1]) > 0 && index(words[i + 1]) < 10) {
                out.add(String.valueOf(20 + index(words[++i])));
                continue;
            }
            out.add(n >= 0 ? String.valueOf(n) : w);
        }
        return String.join(" ", out).replace("h d m i", "hdmi").trim();
    }

    private static int index(String w) {
        for (int i = 0; i < NUMBERS.length; i++) if (NUMBERS[i].equals(w)) return i;
        return -1;
    }

    private static final Pattern SLOT = Pattern.compile("\\{([a-z_]+)\\}");

    /** The best match for `text` among `specs`, or null. */
    static Match match(String text, List<Spec> specs, List<String> wakeWords) {
        String said = normalize(text, wakeWords);
        if (said.isEmpty()) return null;
        Match best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Spec spec : specs) {
            for (String phrase : spec.phrases) {
                Map<String, String> slots = new LinkedHashMap<>();
                int score = tryPhrase(said, phrase, wakeWords, slots);
                if (score > bestScore) {
                    Map<String, String> args = new LinkedHashMap<>(spec.args);
                    args.putAll(slots);
                    best = new Match(spec, args, phrase);
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /** Integer.MIN_VALUE when `phrase` doesn't match all of `said`; else higher is more specific. */
    static int tryPhrase(String said, String phrase, List<String> wakeWords, Map<String, String> slots) {
        // Normalise the phrase's literal words the same way, keeping its {slots}.
        List<String> names = new ArrayList<>();
        Matcher m = SLOT.matcher(phrase.toLowerCase(Locale.ROOT));
        StringBuilder regex = new StringBuilder("^");
        int literalWords = 0, last = 0;
        String lower = phrase.toLowerCase(Locale.ROOT);
        while (m.find()) {
            String lit = normalize(lower.substring(last, m.start()), null);
            if (!lit.isEmpty()) {
                regex.append(Pattern.quote(lit)).append(' ');
                literalWords += lit.split(" ").length;
            }
            regex.append("(.+?)").append(' ');
            names.add(m.group(1));
            last = m.end();
        }
        String tail = normalize(lower.substring(last), null);
        if (!tail.isEmpty()) {
            regex.append(Pattern.quote(tail)).append(' ');
            literalWords += tail.split(" ").length;
        }
        String re = regex.toString().trim() + "$";
        Matcher hit = Pattern.compile(re).matcher(said);
        if (!hit.matches()) return Integer.MIN_VALUE;
        for (int i = 0; i < names.size(); i++) slots.put(names.get(i), hit.group(i + 1).trim());
        return literalWords * 10 - names.size();
    }

    /** config.json "commands" -> specs (entries without an action are skipped). */
    static List<Spec> specs(JSONArray a) {
        List<Spec> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("action").isEmpty()) continue;
            Map<String, String> args = new LinkedHashMap<>();
            JSONObject ja = o.optJSONObject("args");
            if (ja != null) for (Iterator<String> it = ja.keys(); it.hasNext(); ) {
                String k = it.next();
                args.put(k, ja.optString(k));
            }
            List<String> phrases = new ArrayList<>();
            JSONArray p = o.optJSONArray("phrases");
            if (p != null) for (int j = 0; j < p.length(); j++) phrases.add(p.optString(j));
            out.add(new Spec(o.optString("id", o.optString("action") + i), o.optString("action"), args, phrases));
        }
        return out;
    }

    /** Every phrase of every spec, for "what can you do" and `list`. */
    static List<String> phrases(List<Spec> specs) {
        List<String> out = new ArrayList<>();
        for (Spec s : specs) out.addAll(s.phrases);
        Collections.sort(out);
        return out;
    }

    /** "two" / "2" / "hdmi 2" / "input three" -> 2; -1 if no number. */
    static int number(String s) {
        if (s == null) return -1;
        Matcher m = Pattern.compile("(-?\\d+)").matcher(normalize(s, null));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
