package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JARVIS's voice mode: a full-screen overlay over whatever was on screen. It greets, listens (Android's speech
 * recognizer), matches what was heard against the command registry (Intents over config.json "commands"), runs it
 * (Commands) and answers aloud in JARVIS's words (Persona, Voice).
 *
 * Remote: OK listens again (or sends what was typed) · ▼ types instead of speaking · Back closes.
 * Speech recognition, in order: the on-device SpeechRecognizer (Google's, where the TV has it), then the system's
 * speech activity (RecognizerIntent), then typing. The microphone permission is granted by install-launcher.sh
 * (pm grant), or asked here once.
 */
public class JarvisActivity extends Activity implements RecognitionListener {
    private static final int ASK_MIC = 1, SPEECH_ACTIVITY = 2;
    private static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Persona persona;
    private Voice voice;
    private SpeechRecognizer recognizer;
    private HudView view;
    private EditText typed;
    private int state = IDLE;
    private boolean triedActivity, typing, closing, closeAfterReply;
    private String heard = "", reply = "", hint = "";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        if (!UserConfig.jarvisEnabled(this)) {
            // Classic layout: JARVIS is off. Say so and leave (the toggle is in Settings, or `tvctl.sh jarvis on`).
            android.widget.Toast.makeText(this, "JARVIS is off — turn it on in Free My TV > Settings > JARVIS mode.",
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        persona = JarvisHooks.persona(this);
        voice = Voice.get(this);
        voice.configure();
        int accent = UserConfig.color(this, "accent", Color.parseColor("#4FD1FF"));
        FrameLayout root = new FrameLayout(this);
        view = new HudView(this, accent);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        typed = new EditText(this);
        typed.setSingleLine(true);
        typed.setImeOptions(EditorInfo.IME_ACTION_SEND);
        typed.setHint("Type a command, e.g. open Netflix");
        typed.setTextColor(Color.WHITE);
        typed.setHintTextColor(0x99FFFFFF);
        typed.setBackgroundColor(0x33FFFFFF);
        typed.setVisibility(View.GONE);
        typed.setOnEditorActionListener((v, id, ev) -> { submit(typed.getText().toString()); return true; });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        int px = getResources().getDisplayMetrics().widthPixels;
        lp.setMargins(px / 5, 0, px / 5, px / 12);
        root.addView(typed, lp);
        setContentView(root);
        hint = "Try: \"open Netflix\" · \"switch to HDMI 2\" · \"volume down 3\" · \"status report\"";
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (persona == null || closing) return;
        if (state == IDLE && !typing) greetThenListen();
    }

    @Override protected void onPause() {
        super.onPause();
        if (recognizer != null) recognizer.cancel();
        if (state == LISTENING) state = IDLE;
    }

    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void greetThenListen() {
        set(SPEAKING, "", persona.listening());
        voice.say(reply, this::listen);
    }

    private boolean micAllowed() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void listen() {
        if (closing || isFinishing()) return;
        if (typing) return;
        if (recognizer != null && micAllowed()) {
            Intent i = recognizeIntent();
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            set(LISTENING, "", "");
            try {
                recognizer.startListening(i);
                return;
            } catch (RuntimeException e) {
                AppLog.w("JARVIS: recognizer: " + e.getMessage());
            }
        }
        if (recognizer != null && !micAllowed()) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
            return;
        }
        speechActivityOrTyping();
    }

    private Intent recognizeIntent() {
        String lang = UserConfig.jarvis(this).optJSONObject("voice") == null ? "en-GB"
                : UserConfig.jarvis(this).optJSONObject("voice").optString("recognition_language", "en-US");
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, persona.listening());
    }

    /** No usable in-app recognizer: the system's speech screen, once; else typing. */
    private void speechActivityOrTyping() {
        if (!triedActivity) {
            triedActivity = true;
            try {
                startActivityForResult(recognizeIntent(), SPEECH_ACTIVITY);
                set(LISTENING, "", "");
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("JARVIS: no speech activity: " + e.getMessage());
            }
        }
        startTyping(persona.noSpeech());
    }

    private void startTyping(String why) {
        typing = true;
        set(IDLE, "", why);
        voice.say(why, null);
        typed.setVisibility(View.VISIBLE);
        typed.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) main.postDelayed(() -> imm.showSoftInput(typed, InputMethodManager.SHOW_IMPLICIT), 200);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != ASK_MIC) return;
        if (micAllowed()) listen();
        else startTyping("I'll need the microphone for that" + (persona.address.isEmpty() ? "" : ", " + persona.address) + ". You may type instead.");
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code != SPEECH_ACTIVITY) return;
        List<String> said = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (result == RESULT_OK && said != null && !said.isEmpty()) handle(said);
        else startTyping(persona.didNotCatch(""));
    }

    // MARK: - What was heard -> a command -> a reply

    private void submit(String text) {
        if (text == null || text.trim().isEmpty()) return;
        List<String> one = new ArrayList<>();
        one.add(text.trim());
        typed.setText("");
        handle(one);
    }

    /** Candidates from the recognizer, best first: the first one that matches a command wins. */
    private void handle(List<String> candidates) {
        set(THINKING, candidates.get(0), "");
        Context app = getApplicationContext();
        new Thread(() -> {
            String pick = candidates.get(0);
            for (String c : candidates) {
                if (Intents.match(c, UserConfig.specs(app), UserConfig.wakeWords(app)) != null) { pick = c; break; }
            }
            JSONObject r = new JSONObject();
            try {
                Commands.utterance(app, pick, r);
            } catch (JSONException e) {
                AppLog.w("JARVIS: " + e.getMessage());
            }
            String heardText = pick;
            main.post(() -> answer(heardText, r));
        }, "jarvis-command").start();
    }

    private void answer(String heardText, JSONObject r) {
        String action = r.optString("action", "");
        String words;
        if (r.isNull("matched") || !r.has("matched")) {
            words = persona.didNotCatch(heardText);
        } else if (!r.optBoolean("ok")) {
            words = persona.failed(lowerFirst(r.optString("say", "that didn't work")));
        } else {
            words = persona.done(action, Commands.facts(r));
        }
        // After opening an app or an input, JARVIS gets out of the way once it has spoken.
        closeAfterReply = r.optBoolean("ok") && (action.equals("launch_app") || action.equals("hdmi") || action.equals("live_tv")
                || action.equals("home") || action.equals("search") || action.equals("settings") || action.equals("jarvis"));
        set(SPEAKING, heardText, words);
        voice.say(words, () -> {
            if (closeAfterReply) { closing = true; finish(); return; }
            set(IDLE, heard, reply);
            if (typing) { typed.requestFocus(); return; }
            main.postDelayed(() -> { if (state == IDLE && !closing) { closing = true; finish(); } }, 6_000);
        });
    }

    private static String lowerFirst(String s) {
        if (s.isEmpty()) return s;
        String t = s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        return Character.toLowerCase(t.charAt(0)) + t.substring(1);
    }

    private void set(int newState, String heardText, String replyText) {
        state = newState;
        heard = heardText == null ? "" : heardText;
        reply = replyText == null ? "" : replyText;
        if (view != null) view.invalidate();
    }

    // MARK: - Remote

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (code == KeyEvent.KEYCODE_BACK) {
                closing = true;
                if (voice != null) voice.stop();
                finish();
                return true;
            }
            if (!typing && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                if (state != LISTENING) { main.removeCallbacksAndMessages(null); listen(); }
                return true;
            }
            if (!typing && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (recognizer != null) recognizer.cancel();
                startTyping("Go ahead and type" + (persona.address.isEmpty() ? "" : ", " + persona.address) + ".");
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_BACK) return true;   // its release too
        return super.dispatchKeyEvent(e);
    }

    // MARK: - RecognitionListener

    private float level;

    @Override public void onReadyForSpeech(Bundle params) { set(LISTENING, "", ""); }
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rms) {
        level = Math.max(0, Math.min(1, (rms + 2) / 12f));
        if (view != null) view.invalidate();
    }
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { set(THINKING, heard, ""); }

    @Override public void onError(int error) {
        AppLog.w("JARVIS: recognizer error " + error);
        if (closing) return;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: {
                String w = persona.didNotCatch("") + " Press OK to try again.";
                set(IDLE, "", w);
                voice.say(w, null);
                break;
            }
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
                break;
            default:
                // Client, service, network, busy: the in-app recognizer isn't usable here. Fall back.
                if (recognizer != null) { recognizer.destroy(); recognizer = null; }
                speechActivityOrTyping();
        }
    }

    @Override public void onResults(Bundle results) {
        ArrayList<String> said = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (said == null || said.isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return; }
        handle(said);
    }

    @Override public void onPartialResults(Bundle partial) {
        ArrayList<String> said = partial == null ? null : partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (said != null && !said.isEmpty()) { heard = said.get(0); if (view != null) view.invalidate(); }
    }

    @Override public void onEvent(int type, Bundle params) {}

    // MARK: - Drawing

    /** The overlay: a dark veil, the reactor ring (pulses with the voice while listening), what was heard, the reply. */
    private final class HudView extends View {
        private final Paint veil = new Paint(), ring = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG), status = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                said = new TextPaint(Paint.ANTI_ALIAS_FLAG), answer = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private final long born = SystemClock.uptimeMillis();

        HudView(Context c, int accent) {
            super(c);
            setFocusable(false);
            veil.setColor(0xE6050A10);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(accent);
            glow.setColor((accent & 0x00FFFFFF) | 0x33000000);
            name.setColor(accent);
            name.setFakeBoldText(true);
            name.setLetterSpacing(0.35f);
            name.setTextAlign(Paint.Align.CENTER);
            for (TextPaint p : new TextPaint[] {status, said, answer, small}) p.setTextAlign(Paint.Align.CENTER);
            status.setColor(0xB3FFFFFF);
            said.setColor(0xFFFFFFFF);
            answer.setColor(accent);
            small.setColor(0x80FFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), k = w / 1920f, cx = w / 2, cy = h * 0.36f;
            c.drawRect(0, 0, w, h, veil);
            float t = (SystemClock.uptimeMillis() - born) / 1000f;
            float r = 120 * k * (1 + (state == LISTENING ? 0.12f * level : 0));
            c.drawCircle(cx, cy, r * 1.35f, glow);
            ring.setStrokeWidth(4 * k);
            ring.setAlpha(120);
            c.drawCircle(cx, cy, r, ring);
            ring.setAlpha(255);
            ring.setStrokeWidth(9 * k);
            float spin = state == IDLE ? 0 : t * (state == THINKING ? 220 : 60);
            arc.set(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f);
            for (int i = 0; i < 3; i++) c.drawArc(arc, spin + i * 120 + 20, 80, false, ring);
            ring.setStrokeWidth(3 * k);
            arc.set(cx - r * 0.42f, cy - r * 0.42f, cx + r * 0.42f, cy + r * 0.42f);
            c.drawArc(arc, -spin * 1.6f, 300, false, ring);
            name.setTextSize(44 * k);
            c.drawText(spaced(persona.name), cx, cy + r * 1.35f + 70 * k, name);
            status.setTextSize(28 * k);
            String st = state == LISTENING ? "Listening…" : state == THINKING ? "Working on it…" : state == SPEAKING ? "" : typing ? "Typing" : "OK to talk · Back to close";
            c.drawText(st, cx, cy + r * 1.35f + 118 * k, status);
            float y = h * 0.70f;
            said.setTextSize(40 * k);
            if (!heard.isEmpty()) c.drawText(fit("“" + heard + "”", said, w * 0.8f), cx, y, said);
            answer.setTextSize(36 * k);
            if (!reply.isEmpty()) drawWrapped(c, reply, answer, cx, y + 70 * k, w * 0.7f, 48 * k);
            small.setTextSize(24 * k);
            c.drawText(hint + " · ▼ to type", cx, h - 50 * k, small);
            if (state != IDLE) postInvalidateOnAnimation();
        }

        private String spaced(String s) {
            StringBuilder b = new StringBuilder();
            for (char ch : s.toUpperCase(Locale.ROOT).toCharArray()) b.append(ch).append('.');
            return b.toString();
        }

        private String fit(String s, TextPaint p, float max) {
            return TextUtils.ellipsize(s, p, max, TextUtils.TruncateAt.END).toString();
        }

        private void drawWrapped(Canvas c, String text, TextPaint p, float cx, float y, float max, float lh) {
            String[] words = text.split(" ");
            StringBuilder line = new StringBuilder();
            int lines = 0;
            for (String word : words) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (p.measureText(next) > max && line.length() > 0) {
                    c.drawText(line.toString(), cx, y + lines++ * lh, p);
                    line = new StringBuilder(word);
                    if (lines == 3) break;
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (lines < 3 && line.length() > 0) c.drawText(line.toString(), cx, y + lines * lh, p);
        }
    }
}
