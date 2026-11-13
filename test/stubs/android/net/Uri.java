package android.net;

/** Just enough of Uri for the JVM tests. */
public final class Uri {
    private final java.net.URI u;
    private Uri(java.net.URI u) { this.u = u; }
    public static Uri parse(String s) { return new Uri(java.net.URI.create(s)); }
    public String getHost() { return u.getHost(); }
}
