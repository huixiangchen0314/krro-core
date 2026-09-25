package top.kzre.krro.core.util;

public final class DEBUG {
    private DEBUG() {}
    public static String stack() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        // st[0]=stack(),st[1]=调用点
        for (int i = 1; i < st.length; i++) {
            sb.append("\n>>>>called at ").append(st[i]);
        }
        return sb.toString();
    }
}
