package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The JARVIS layout's mark on the home screen: one glass pill at the top centre (between the title and the round
 * buttons, which Free My TV leaves free) — a small arc-reactor ring, "JARVIS", the greeting, and how to talk.
 *
 * It sits OVER the home screen as a separate view (Activity.addContentView), never inside HomeView: switching JARVIS off
 * removes this view and nothing else changes, which is what makes the toggle a safe rollback. It doesn't take focus,
 * keys or clicks, and redraws once a minute (the greeting), never per frame.
 */
final class JarvisHud extends View {
    static final int WIDTH = 820, HEIGHT = 64, TOP = 48;   // at 1920x1080; scaled to the screen

    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), title = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF(), arc = new RectF();
    private final Persona persona;
    private final String hintText;
    private final int accent;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();
            postDelayed(this, 60_000);
        }
    };

    JarvisHud(Context c, Persona persona, String hintText, int accent) {
        super(c);
        this.persona = persona;
        this.hintText = hintText;
        this.accent = accent;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        glass.setColor(0xB30B1620);
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor((accent & 0x00FFFFFF) | 0x66000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setColor(accent);
        title.setColor(accent);
        title.setFakeBoldText(true);
        title.setLetterSpacing(0.18f);
        text.setColor(0xF2FFFFFF);
        hint.setColor(0x99FFFFFF);
        hint.setTextAlign(Paint.Align.RIGHT);
    }

    void resume() { removeCallbacks(tick); post(tick); }
    void pause() { removeCallbacks(tick); }

    @Override protected void onDraw(Canvas c) {
        float k = getWidth() / (float) WIDTH, h = getHeight();
        box.set(1, 1, getWidth() - 1, h - 1);
        float r = h / 2f;
        c.drawRoundRect(box, r, r, glass);
        rim.setStrokeWidth(1.5f * k);
        c.drawRoundRect(box, r, r, rim);
        // The ring: an outer circle and three arcs, the "reactor".
        float cx = r + 6 * k, cy = h / 2f, rr = h * 0.30f;
        ring.setStrokeWidth(2.2f * k);
        ring.setAlpha(110);
        c.drawCircle(cx, cy, rr, ring);
        ring.setAlpha(255);
        ring.setStrokeWidth(3.2f * k);
        arc.set(cx - rr * 0.62f, cy - rr * 0.62f, cx + rr * 0.62f, cy + rr * 0.62f);
        for (int i = 0; i < 3; i++) c.drawArc(arc, i * 120 + 15, 80, false, ring);
        float x = cx + rr + 18 * k, base = cy + 8 * k;
        title.setTextSize(22 * k);
        c.drawText(persona.name, x, base, title);
        x += title.measureText(persona.name) + 18 * k;
        text.setTextSize(23 * k);
        c.drawText(persona.greeting(), x, base, text);
        hint.setTextSize(19 * k);
        c.drawText(hintText, getWidth() - r, base, hint);
    }
}
