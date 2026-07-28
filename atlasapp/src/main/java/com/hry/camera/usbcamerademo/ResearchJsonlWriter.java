package com.hry.camera.usbcamerademo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;

/** Synchronously appends complete JSONL rows and retries transient failures in order. */
final class ResearchJsonlWriter {
    interface LineSink {
        void appendAndSync(String line) throws IOException;
    }

    interface ErrorReporter {
        void onWriteFailure(IOException error, int pendingCount);
    }

    private static final int DEFAULT_MAX_PENDING = 100;
    private static final ErrorReporter NO_OP_REPORTER = new ErrorReporter() {
        @Override
        public void onWriteFailure(IOException error, int pendingCount) {
        }
    };

    private final LineSink sink;
    private final int maxPending;
    private final ErrorReporter reporter;
    private final ArrayDeque<String> pending = new ArrayDeque<>();

    ResearchJsonlWriter(File file, ErrorReporter reporter) {
        this(new FileLineSink(file), DEFAULT_MAX_PENDING, reporter);
    }

    ResearchJsonlWriter(
            LineSink sink,
            int maxPending,
            ErrorReporter reporter
    ) {
        if (sink == null) {
            throw new IllegalArgumentException("sink == null");
        }
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending < 1");
        }
        this.sink = sink;
        this.maxPending = maxPending;
        this.reporter = reporter == null ? NO_OP_REPORTER : reporter;
    }

    synchronized boolean append(JSONObject json) {
        if (json == null) {
            return false;
        }
        String line = json.toString();
        try {
            while (!pending.isEmpty()) {
                String pendingLine = pending.peekFirst();
                sink.appendAndSync(pendingLine);
                pending.removeFirst();
            }
            sink.appendAndSync(line);
            return true;
        } catch (IOException error) {
            enqueueBounded(line);
            reporter.onWriteFailure(error, pending.size());
            return false;
        }
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    private void enqueueBounded(String line) {
        if (pending.size() >= maxPending) {
            pending.removeFirst();
        }
        pending.addLast(line);
    }

    private static final class FileLineSink implements LineSink {
        private final File file;

        FileLineSink(File file) {
            if (file == null) {
                throw new IllegalArgumentException("file == null");
            }
            this.file = file;
        }

        @Override
        public void appendAndSync(String line) throws IOException {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException(
                        "Could not create research log directory");
            }
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(file, true);
                output.write(line.getBytes(Charset.forName("UTF-8")));
                output.write('\n');
                output.flush();
                output.getFD().sync();
            } finally {
                if (output != null) {
                    output.close();
                }
            }
        }
    }
}
