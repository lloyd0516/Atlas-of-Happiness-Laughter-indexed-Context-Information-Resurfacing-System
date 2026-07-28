package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.Event.COMPLETED;
import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.Event.FAILED;
import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.Event.PLAY_REQUESTED;
import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.Event.STOPPED;
import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.Event.TOGGLE_REQUESTED;
import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.Status.ERROR;
import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.Status.IDLE;
import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.Status.PAUSED;
import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.Status.PLAYING;
import static com.hry.camera.usbcamerademo.AtlasAudioPlaybackState.transition;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AtlasAudioPlaybackStateTest {
    @Test
    public void sameClipTogglesBetweenPlayingAndPaused() {
        AtlasAudioPlaybackState.State playing =
                transition(idle(), PLAY_REQUESTED, "a.wav");
        assertEquals(PLAYING, playing.status);

        AtlasAudioPlaybackState.State paused =
                transition(playing, TOGGLE_REQUESTED, "a.wav");
        assertEquals(PAUSED, paused.status);

        AtlasAudioPlaybackState.State resumed =
                transition(paused, TOGGLE_REQUESTED, "a.wav");
        assertEquals(PLAYING, resumed.status);
    }

    @Test
    public void differentClipReplacesCurrentPlayback() {
        AtlasAudioPlaybackState.State first =
                transition(idle(), PLAY_REQUESTED, "a.wav");

        AtlasAudioPlaybackState.State second =
                transition(first, PLAY_REQUESTED, "b.wav");

        assertEquals(PLAYING, second.status);
        assertEquals("b.wav", second.path);
    }

    @Test
    public void completionStopAndFailureClearActivePath() {
        AtlasAudioPlaybackState.State playing =
                transition(idle(), PLAY_REQUESTED, "a.wav");

        AtlasAudioPlaybackState.State completed =
                transition(playing, COMPLETED, null);
        AtlasAudioPlaybackState.State stopped =
                transition(playing, STOPPED, null);
        AtlasAudioPlaybackState.State failed =
                transition(playing, FAILED, null);

        assertEquals(IDLE, completed.status);
        assertEquals(IDLE, stopped.status);
        assertEquals(ERROR, failed.status);
        assertNull(completed.path);
        assertNull(stopped.path);
        assertNull(failed.path);
    }

    private static AtlasAudioPlaybackState.State idle() {
        return new AtlasAudioPlaybackState.State(null, IDLE);
    }
}
