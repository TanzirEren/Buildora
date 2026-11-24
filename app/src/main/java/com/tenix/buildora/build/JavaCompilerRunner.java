package com.tenix.buildora.build;

/** Runs one ECJ compilation. Implementations: in this process, or in a separate process (annotation processing). */
public interface JavaCompilerRunner {

    interface Cancel { boolean isCancelled(); }

    final class Result {
        public boolean ok;
        public String output = "";
        /** The compiler itself threw (stack trace text), as opposed to reporting compile errors. */
        public String crash;
        public boolean timedOut, died, cancelled;
        /** The isolated process could not be started: the caller falls back to running in this process. */
        public boolean unavailable;
    }

    Result run(String[] argv, long timeoutMs, Cancel cancel);
}
