package com.hry.camera.usbcamerademo;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/** Generates opaque research identifiers without exposing paths or location keys. */
final class ResearchIdentifiers {
    private ResearchIdentifiers() {
    }

    static String notificationInstanceId() {
        return "notification_" + UUID.randomUUID().toString();
    }

    static String anonymousId(String namespace, String raw) {
        String safeNamespace = namespace == null || namespace.length() == 0
                ? "id" : namespace.toLowerCase(Locale.US);
        if (raw == null || raw.length() == 0) {
            return safeNamespace + "_missing";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    (safeNamespace + "\n" + raw)
                            .getBytes(Charset.forName("UTF-8")));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
            }
            return safeNamespace + "_" + hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return safeNamespace + "_"
                    + Integer.toHexString(raw.hashCode());
        }
    }
}
