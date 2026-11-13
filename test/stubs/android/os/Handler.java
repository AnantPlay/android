package android.os;

import java.util.ArrayList;
import java.util.List;

/** Queues what would run later; a test runs it with drain(). */
public class Handler {
    private static final List<Runnable> queue = new ArrayList<>();
    public boolean post(Runnable r) { queue.add(r); return true; }
    public boolean postDelayed(Runnable r, long ms) { queue.add(r); return true; }
    public static void drain() { while (!queue.isEmpty()) queue.remove(0).run(); }
}
