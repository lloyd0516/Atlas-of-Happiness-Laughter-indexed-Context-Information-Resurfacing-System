package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AtlasEventDeletionPathsTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void includesOwnedMediaAndRejectsOutsidePaths() throws Exception {
        File root = temporaryFolder.newFolder("joyful_moment");
        File session = new File(root, "session-a");
        assertTrue(session.mkdirs());
        File eventFile = new File(session, "event-a.json");
        File ownedAudio = new File(session, "captured_media/event-a/laugh.wav");
        File ownedPhoto = new File(session, "user_generated/event-a/photo.jpg");
        File outside = temporaryFolder.newFile("outside.mp4");

        JSONObject event = new JSONObject()
                .put("event_id", "event-a")
                .put("auto_captured", new JSONObject()
                        .put("audio_clips", new JSONArray()
                                .put(new JSONObject().put("path", ownedAudio.getPath())))
                        .put("photos", new JSONArray()
                                .put(new JSONObject().put("photo_path", ownedPhoto.getPath())))
                        .put("videos", new JSONArray()
                                .put(new JSONObject().put("video_path", outside.getPath()))));

        List<File> targets = AtlasReviewRepository.collectOwnedDeletionTargets(
                event, root, eventFile);
        assertTrue(containsCanonical(targets, ownedAudio));
        assertTrue(containsCanonical(targets, ownedPhoto));
        assertTrue(containsCanonical(targets, eventFile));
        assertFalse(containsCanonical(targets, outside));
    }

    private boolean containsCanonical(List<File> files, File expected) throws Exception {
        String path = expected.getCanonicalPath();
        for (File file : files) {
            if (path.equals(file.getCanonicalPath())) {
                return true;
            }
        }
        return false;
    }
}
