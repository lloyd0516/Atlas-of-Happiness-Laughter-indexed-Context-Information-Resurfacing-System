package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResearchJsonlWriterTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appendsIndependentJsonLines() throws Exception {
        File file = temporaryFolder.newFile("research.jsonl");
        ResearchJsonlWriter writer = new ResearchJsonlWriter(file, null);

        assertTrue(writer.append(new JSONObject().put("event_id", "first")));
        assertTrue(writer.append(new JSONObject().put("event_id", "second")));

        List<String> lines = Files.readAllLines(
                file.toPath(), Charset.forName("UTF-8"));
        assertEquals(2, lines.size());
        assertEquals("first", new JSONObject(lines.get(0)).getString("event_id"));
        assertEquals("second", new JSONObject(lines.get(1)).getString("event_id"));
    }

    @Test
    public void failedLineIsRetriedBeforeNewLine() throws Exception {
        FailOnceSink sink = new FailOnceSink();
        ResearchJsonlWriter writer = new ResearchJsonlWriter(sink, 10, null);

        assertFalse(writer.append(new JSONObject().put("event_id", "first")));
        assertEquals(1, writer.pendingCount());
        assertTrue(writer.append(new JSONObject().put("event_id", "second")));

        assertEquals(Arrays.asList("first", "second"), sink.eventIds);
        assertEquals(0, writer.pendingCount());
    }

    private static final class FailOnceSink
            implements ResearchJsonlWriter.LineSink {
        private boolean failed;
        final List<String> eventIds = new ArrayList<>();

        @Override
        public void appendAndSync(String line) throws IOException {
            if (!failed) {
                failed = true;
                throw new IOException("first write fails");
            }
            try {
                eventIds.add(new JSONObject(line).getString("event_id"));
            } catch (Exception error) {
                throw new IOException(error);
            }
        }
    }
}
