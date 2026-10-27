package app.grain.dev;

/** Interactive pseudoterminals. Each open returns a handle for one shell. */
final class GrainPty {
    static {
        System.loadLibrary("grain_pty");
    }

    private GrainPty() {}

    static native String lastError();

    /** Session handle, or -1 when the shell could not start. */
    static native int open(String cwd, String[] env, int cols, int rows);

    /** Positive byte count, 0 on timeout, -2 when the shell has closed the pty. */
    static native int read(int handle, byte[] buffer, int timeoutMs);

    static native int write(int handle, byte[] data);

    static native void resize(int handle, int cols, int rows);

    static native void interrupt(int handle);

    static native void close(int handle);

    static native void closeAll();
}
