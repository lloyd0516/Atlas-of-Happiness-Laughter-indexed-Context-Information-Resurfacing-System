package com.hry.camera.usbcamerademo;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class JoyfulMomentEventStore {
    private final File rootDir;
    private final SimpleDateFormat fileTimeFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    public JoyfulMomentEventStore(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        rootDir = new File(base, "joyful_moment");
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
    }

    public File createSessionDir(String sessionId) {
        File dir = new File(rootDir, sessionId);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public synchronized void appendJsonLine(File file, JSONObject json) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream outputStream = new FileOutputStream(file, true);
            outputStream.write(json.toString().getBytes(Charset.forName("UTF-8")));
            outputStream.write('\n');
            outputStream.close();
        } catch (IOException ignored) {
        }
    }

    public synchronized void writeJson(File file, JSONObject json) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream outputStream = new FileOutputStream(file, false);
            outputStream.write(json.toString(2).getBytes(Charset.forName("UTF-8")));
            outputStream.close();
        } catch (IOException ignored) {
        } catch (JSONException ignored) {
        }
    }

    public String newSessionId() {
        return "session_" + fileTimeFormat.format(new Date());
    }
}
