package android.util;

@SuppressWarnings({"unused", "SameReturnValue"})
public class Log {
    public static int d(String tag, String msg) {
        System.out.println("[" + tag + "] DEBUG: " + msg);
        return 0;
    }

    public static int i(String tag, String msg) {
        System.out.println("[" + tag + "] INFO: " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.err.println("[" + tag + "] WARN: " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        System.err.println("[" + tag + "] WARN: " + msg);
        if (tr != null) tr.printStackTrace();
        return 0;
    }

    public static int e(String tag, String msg) {
        System.err.println("[" + tag + "] ERROR: " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        System.err.println("[" + tag + "] ERROR: " + msg);
        if (tr != null) tr.printStackTrace();
        return 0;
    }
}
