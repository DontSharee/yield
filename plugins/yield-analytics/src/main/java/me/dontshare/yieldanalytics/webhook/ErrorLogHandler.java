package me.dontshare.yieldanalytics.webhook;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Forwards SEVERE log records - a plugin error, a task that threw - to the
 * webhooks' {@code error} event. At most one post a minute: the first error
 * goes straight out, anything after it in that minute is counted and noted
 * on the next one, so a failure loop can't flood a channel.
 */
public final class ErrorLogHandler extends Handler {

    private static final long WINDOW_MILLIS = 60_000L;

    private final WebhookService webhooks;
    private long windowStart;
    private int suppressed;

    public ErrorLogHandler(WebhookService webhooks) {
        this.webhooks = webhooks;
        setLevel(Level.SEVERE);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (record == null || record.getLevel().intValue() < Level.SEVERE.intValue()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - windowStart < WINDOW_MILLIS) {
            suppressed++;
            return;
        }
        windowStart = now;
        StringBuilder text = new StringBuilder();
        if (suppressed > 0) {
            text.append("(+").append(suppressed).append(" more in the last minute)\n");
            suppressed = 0;
        }
        text.append('[').append(record.getLoggerName()).append("] ");
        text.append(record.getMessage() == null ? "" : record.getMessage());
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            text.append('\n').append(trace);
        }
        webhooks.error(text.toString());
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
