package ru.murad.myvpn.exception;

/** Builds diagnostics from exception types and stack frames only; it never reads exception messages. */
public final class SafeExceptionLogFormatter {
    private static final int MAX_CAUSES = 4;
    private static final int MAX_FRAMES_PER_CAUSE = 20;

    private SafeExceptionLogFormatter() {
    }

    public static String format(Throwable failure) {
        StringBuilder result = new StringBuilder(512);
        Throwable current = failure;
        for (int cause = 0; current != null && cause < MAX_CAUSES; cause++, current = current.getCause()) {
            if (cause > 0) {
                result.append("causedBy=");
            } else {
                result.append("exceptionType=");
            }
            result.append(current.getClass().getName()).append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            int upper = Math.min(frames.length, MAX_FRAMES_PER_CAUSE);
            for (int index = 0; index < upper; index++) {
                StackTraceElement frame = frames[index];
                result.append("  at ").append(frame.getClassName()).append('.')
                        .append(frame.getMethodName()).append('(')
                        .append(frame.getFileName() == null ? "Unknown" : frame.getFileName())
                        .append(':').append(frame.getLineNumber()).append(")\n");
            }
            if (frames.length > upper) {
                result.append("  ... frames truncated\n");
            }
        }
        if (current != null) {
            result.append("causes truncated\n");
        }
        return result.toString();
    }
}
