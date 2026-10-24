package com.tv.simplelive;

/** Each cleanup stage is independent; a Java/JNI linkage error must not skip later stages. */
final class PlayerCleanup {
    static boolean run(String stage, Runnable action) {
        try { action.run(); return true; }
        catch (RuntimeException error) { report(stage, error); return false; }
        catch (LinkageError error) { report(stage, error); return false; }
        catch (AssertionError error) { report(stage, error); return false; }
    }
    private static void report(String stage, Throwable error) {
        // Do not log source URLs or exception messages that may contain credentials.
        android.util.Log.w("SimpleLiveTV", "player cleanup " + stage + ": " + error.getClass().getSimpleName());
    }
}
