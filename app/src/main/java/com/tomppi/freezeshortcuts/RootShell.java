package com.tomppi.freezeshortcuts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class RootShell {
    private static final Pattern PACKAGE_RE = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");

    private RootShell() {}

    public static final class Result {
        public final int exitCode;
        public final String output;
        public final boolean timedOut;

        Result(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        @Override
        public String toString() {
            return "exit=" + exitCode + ", timedOut=" + timedOut + ", output=" + output;
        }
    }

    public static boolean isValidPackageName(String packageName) {
        return packageName != null && PACKAGE_RE.matcher(packageName).matches();
    }

    public static Result hasRoot() {
        return run("id", 8000);
    }

    public static Result enablePackage(String packageName) {
        if (!isValidPackageName(packageName)) {
            return new Result(2, "Refusing invalid package name: " + packageName, false);
        }
        Result r = run("pm enable --user 0 " + packageName, 20000);
        if (r.ok()) return r;
        Result fallback = run("pm enable " + packageName, 20000);
        return fallback.ok() ? fallback : r;
    }

    public static Result disablePackage(String packageName) {
        if (!isValidPackageName(packageName)) {
            return new Result(2, "Refusing invalid package name: " + packageName, false);
        }
        Result r = run("pm disable-user --user 0 " + packageName, 20000);
        if (r.ok()) return r;
        Result fallback = run("pm disable-user " + packageName, 20000);
        return fallback.ok() ? fallback : r;
    }

    public static Result run(String command, long timeoutMs) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        Thread reader = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final Process p = process;
            reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "root-output-reader");
            reader.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(-1, safeString(output), true);
            }
            if (reader != null) reader.join(1000);
            return new Result(process.exitValue(), safeString(output), false);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new Result(-1, e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    private static String safeString(StringBuilder builder) {
        synchronized (builder) {
            return builder.toString();
        }
    }

    public static boolean outputLooksRoot(Result result) {
        String out = result.output.toLowerCase(Locale.ROOT);
        return result.ok() && (out.contains("uid=0") || out.contains("root"));
    }
}
