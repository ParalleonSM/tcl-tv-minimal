package io.freemytv;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JARVIS's voice: Android's text-to-speech (the TV's engine, normally Google's com.google.android.tts, which is on
 * config/do-not-touch.list for this reason). config.json jarvis.voice: language ("en-GB"), rate, pitch, and an exact
 * voice name if you want one (`adb shell cmd tts …` isn't on every TV; the status reply lists what the engine offers).
 * One per process, created on the main thread; say() may be called from any thread.
 */
final class Voice extends UtteranceProgressListener implements TextToSpeech.OnInitListener {
    private static Voice instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready, failed;
    private String pendingText;
    private Runnable pendingDone;
    private final Map<String, Runnable> done = new HashMap<>();
    private int ids;

    static synchronized Voice get(Context c) {
        if (instance == null) instance = new Voice(c.getApplicationContext());
        return instance;
    }

    private Voice(Context c) {
        app = c;
        main.post(() -> tts = new TextToSpeech(app, this));
    }

    @Override public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            failed = true;
            AppLog.w("JARVIS: text-to-speech didn't start (" + status + ")");
            runPendingDone();
            return;
        }
        tts.setOnUtteranceProgressListener(this);
        configure();
        ready = true;
        if (pendingText != null) {
            String t = pendingText;
            Runnable d = pendingDone;
            pendingText = null;
            pendingDone = null;
            say(t, d);
        }
    }

    /** jarvis.voice from config.json: language, rate, pitch, name. */
    void configure() {
        if (tts == null) return;
        JSONObject v = UserConfig.jarvis(app).optJSONObject("voice");
        if (v == null) v = new JSONObject();
        Locale locale = Locale.forLanguageTag(v.optString("language", "en-GB"));
        int r = tts.setLanguage(locale);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
        tts.setSpeechRate((float) Math.max(0.5, Math.min(2.0, v.optDouble("rate", 1.0))));
        tts.setPitch((float) Math.max(0.5, Math.min(2.0, v.optDouble("pitch", 0.9))));
        String name = v.optString("name", "");
        if (!name.isEmpty()) {
            try {
                for (android.speech.tts.Voice voice : tts.getVoices()) {
                    if (voice.getName().equals(name)) { tts.setVoice(voice); break; }
                }
            } catch (RuntimeException e) {
                AppLog.w("JARVIS: voices: " + e.getMessage());
            }
        }
    }

    /** The engine's voice names for the configured language (for jarvis.voice.name); empty until ready. */
    String voices() {
        StringBuilder out = new StringBuilder();
        try {
            Set<android.speech.tts.Voice> all = tts == null || !ready ? null : tts.getVoices();
            if (all != null) for (android.speech.tts.Voice v : all) {
                if (v.getLocale().getLanguage().equals("en")) out.append(out.length() == 0 ? "" : ",").append(v.getName());
            }
        } catch (RuntimeException ignored) {
        }
        return out.toString();
    }

    /** Speaks `text`; `after` runs on the main thread once it's done (or right away if speech isn't there). */
    void say(String text, Runnable after) {
        if (text == null || text.trim().isEmpty()) { if (after != null) main.post(after); return; }
        main.post(() -> {
            if (failed) { if (after != null) after.run(); return; }
            if (!ready) { pendingText = text; pendingDone = after; return; }
            String id = "j" + (++ids);
            if (after != null) done.put(id, after);
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) finished(id);
        });
    }

    void stop() {
        main.post(() -> {
            if (tts != null && ready) tts.stop();
            for (Runnable r : done.values()) main.post(r);
            done.clear();
        });
    }

    private void runPendingDone() {
        if (pendingDone != null) main.post(pendingDone);
        pendingText = null;
        pendingDone = null;
    }

    private void finished(String id) {
        main.post(() -> {
            Runnable r = done.remove(id);
            if (r != null) r.run();
        });
    }

    @Override public void onStart(String id) {}
    @Override public void onDone(String id) { finished(id); }
    @Override public void onError(String id) { finished(id); }
    @Override public void onStop(String id, boolean interrupted) { finished(id); }
}
