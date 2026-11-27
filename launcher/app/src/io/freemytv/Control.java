package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * The agent door: content://io.freemytv.control, for adb's shell only (as Secrets: Binder.getCallingUid() is the
 * shell's 2000, or root). No other app on the TV can use it, and nothing listens on the network.
 *
 *   adb exec-in content write --uri content://io.freemytv.control/config.json < config.json   (push a config)
 *   adb shell content read --uri content://io.freemytv.control/config.json                    (read it back)
 *   adb shell content call --uri content://io.freemytv.control --method command --arg '{"action":"status"}'
 *       -> Result: Bundle[{reply={"ok":true,...}}]
 *
 * scripts/tvctl.sh and scripts/push-config.sh wrap these. Runs in :home, with the home screen and JARVIS.
 */
public class Control extends ContentProvider {
    private static final int SHELL_UID = 2000;

    private static void check() {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb's shell may drive the launcher");
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        check();
        Bundle out = new Bundle();
        JSONObject reply;
        if (!"command".equals(method)) {
            reply = error("unknown method " + method + " (use --method command)");
        } else {
            try {
                reply = Commands.run(getContext(), new JSONObject(arg == null ? "{}" : arg));
            } catch (JSONException e) {
                reply = error("--arg must be a JSON object: " + e.getMessage());
            }
        }
        out.putString("reply", reply.toString());
        return out;
    }

    private static JSONObject error(String why) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("say", why);
        } catch (JSONException ignored) {
        }
        return o;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        check();
        if (!UserConfig.FILE.equals(uri.getLastPathSegment())) throw new FileNotFoundException("only " + UserConfig.FILE);
        File dir = getContext().getFilesDir();
        if (!mode.contains("w")) {
            // Read: the user's file, or the defaults written out once so there is something to read and edit.
            File f = UserConfig.file(getContext());
            if (!f.isFile()) {
                try (FileOutputStream o = new FileOutputStream(new File(dir, ".defaults.json"))) {
                    o.write(UserConfig.get(getContext()).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (IOException | JSONException e) {
                    throw new FileNotFoundException(e.getMessage());
                }
                f = new File(dir, ".defaults.json");
            }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        // Write: under a hidden name; taken (validated, saved, applied) only once it is whole and parses.
        File part = new File(dir, ".incoming-" + UserConfig.FILE);
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        String why;
                        try {
                            why = part.length() > 512 * 1024 ? "too big" : UserConfig.accept(getContext(), Files.readAllBytes(part.toPath()));
                        } catch (IOException ex) {
                            why = ex.getMessage();
                        }
                        part.delete();
                        AppLog.w("config.json " + (why == null ? "received and applied" : "refused: " + why));
                    });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
