package me.champdev.weblock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lines a subtitle file up with the video's speech (ffsubsync's idea, small): the player measures how loud the dialogue band
 * is every 0.1 s (SpeechMeter); each bin is taken against the 2 s around it, so pauses stand out and a loud scene doesn't pull
 * the lines to it. The file's lines are tried at every shift up to 4 minutes either way and at the frame-rate stretches; the
 * place where the lines sit on the louder bins wins when it stands well clear of every other. Plain Java (test/SubSyncTest.java).
 * Checked against real films (2026-11): the sound of Tears of Steel and His Girl Friday run through SpeechMeter, their
 * subtitle releases shifted and stretched; at SHARP 6 no wrong answer, the raw-loudness version was wrong almost always.
 */
final class SubSync {
    static final int BIN_MS = 100, RANGE_MS = 240_000;
    static final int LOCAL = 20;   // bins either side a bin's loudness is measured against
    static double SHARP = 6;
    /** What the last fit saw, per rate (for the log). */
    static String why = "";

    /** Start and end (ms) of every line in an .srt, .vtt or .ass file. */
    static List<long[]> cues(File f) throws IOException {
        return cues(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    static final Pattern ARROW = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    static final Pattern SHORT_ARROW = Pattern.compile("(?m)^(\\d{2}):(\\d{2})[.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2})[.](\\d{3})");   // WebVTT mm:ss.ttt
    static final Pattern ASS = Pattern.compile("(?m)^Dialogue:\\s*[^,]*,(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),");

    static List<long[]> cues(String text) {
        List<long[]> out = new ArrayList<>();
        Matcher m = ARROW.matcher(text);
        while (m.find()) out.add(new long[]{ms(m, 1), ms(m, 5)});
        if (out.isEmpty()) {
            Matcher v = SHORT_ARROW.matcher(text);
            while (v.find()) out.add(new long[]{(Long.parseLong(v.group(1)) * 60 + Long.parseLong(v.group(2))) * 1000 + Long.parseLong(v.group(3)),
                (Long.parseLong(v.group(4)) * 60 + Long.parseLong(v.group(5))) * 1000 + Long.parseLong(v.group(6))});
        }
        Matcher a = ASS.matcher(text);
        while (a.find()) out.add(new long[]{ms(a, 1), ms(a, 5)});
        return out;
    }

    private static long ms(Matcher m, int g) {
        String frac = m.group(g + 3);
        long f = Long.parseLong(frac) * (frac.length() == 1 ? 100 : frac.length() == 2 ? 10 : 1);
        return ((Long.parseLong(m.group(g)) * 60 + Long.parseLong(m.group(g + 1))) * 60 + Long.parseLong(m.group(g + 2))) * 1000 + f;
    }

    /** Where a file's lines belong: a line timed t (ms) shows at t * rate + offsetMs of the video. */
    static final class Fit {
        final long offsetMs;
        final double rate;

        Fit(long offsetMs, double rate) { this.offsetMs = offsetMs; this.rate = rate; }

        long at(long t) { return Math.round(t * rate) + offsetMs; }
    }

    /** A file made for another frame rate drifts: 25 fps (PAL) against 23.976 (film and web) is 3.7 minutes over 90. */
    static final double[] RATES = rates();

    /** The frame-rate pairs, then every 0.25 % from -3 % to +3 % (an old print or a sped-up copy drifts by an odd amount). */
    private static double[] rates() {
        double[] fixed = {1, 25 / 23.976, 23.976 / 25, 24 / 23.976, 23.976 / 24, 25 / 24.0, 24 / 25.0};
        double[] out = java.util.Arrays.copyOf(fixed, fixed.length + 24);
        for (int i = 0, k = fixed.length; i <= 24; i++) if (i != 12) out[k++] = 0.97 + i * 0.0025;
        return out;
    }

    /**
     * Where the lines sit on the speech, or null when unsure: too little measured, too few lines in it, or no clear winner.
     * level[i] = loudness of the dialogue band in 0.1 s bin i of the video, negative = not measured (not played yet).
     * Every rate in RATES is tried at every shift up to 4 minutes either way; another rate than 1 must clearly beat 1 (over a
     * short stretch they all fit the same, and most files are right).
     */
    static Fit fit(float[] level, List<long[]> cues) {
        int n = level.length, measured = 0, first = -1, last = 0;
        double sum, sq;
        for (int i = 0; i < n; i++) if (level[i] >= 0) {
            measured++;
            if (first < 0) first = i;
            last = i;
        }
        if (measured < 900) return null;                       // 90 s of sound at least
        // Loudness against the 2 s around it: speech stands out from its pauses, while a loud scene (a fight, music) is not
        // "more speech" (on raw loudness the lines were drawn to the loudest stretch of the film, not to the talking)
        double[] lg = new double[n], ls = new double[n + 1];
        int[] lc = new int[n + 1];
        for (int i = 0; i < n; i++) {
            boolean m = level[i] >= 0;
            lg[i] = m ? Math.log(level[i] + 1e-9) : 0;
            ls[i + 1] = ls[i] + lg[i];
            lc[i + 1] = lc[i] + (m ? 1 : 0);
        }
        sum = 0; sq = 0;
        for (int i = 0; i < n; i++) if (level[i] >= 0) {
            int a = Math.max(0, i - LOCAL), b = Math.min(n, i + LOCAL + 1);
            lg[i] -= (ls[b] - ls[a]) / (lc[b] - lc[a]);
            sum += lg[i]; sq += lg[i] * lg[i];
        }
        double mean = sum / measured, sd = Math.sqrt(Math.max(1e-12, sq / measured - mean * mean));
        // prefix sums over the video's bins: z (speech score, 0 where not measured) and how many were measured
        double[] zs = new double[n + 1];
        int[] cs = new int[n + 1];
        for (int i = 0; i < n; i++) {
            boolean m = level[i] >= 0;
            zs[i + 1] = zs[i] + (m ? (lg[i] - mean) / sd : 0);
            cs[i + 1] = cs[i] + (m ? 1 : 0);
        }
        int range = RANGE_MS / BIN_MS;
        Fit best = null;
        double bestSharp = 0, bestOne = 0;
        why = "";
        for (double rate : RATES) {
            List<int[]> on = spans(cues, rate, first - range, last + range);
            double[] score = new double[2 * range + 1];
            int[] count = new int[2 * range + 1];
            for (int d = -range; d <= range; d++) {
                double s = 0;
                int c = 0;
                for (int[] sp : on) {   // with the lines moved d later, the line at bins [a, b] shows at [a + d, b + d]
                    int a = Math.max(0, Math.min(n, sp[0] + d)), b = Math.max(0, Math.min(n, sp[1] + d + 1));
                    s += zs[b] - zs[a];
                    c += cs[b] - cs[a];
                }
                score[d + range] = c > 0 ? s / Math.sqrt(c) : 0;
                count[d + range] = c;
            }
            int top = 0;
            double all = 0, all2 = 0;
            for (int i = 0; i < score.length; i++) { if (score[i] > score[top]) top = i; all += score[i]; all2 += score[i] * score[i]; }
            double second = 0;
            for (int i = 0; i < score.length; i++) if (Math.abs(i - top) > 20 && score[i] > second) second = score[i];   // 2 s away
            // how far the winner stands above every other shift, in standard deviations of them all (a wrong file or noise: about 4)
            double m = all / score.length, sharp = (score[top] - m) / Math.sqrt(Math.max(1e-12, all2 / score.length - m * m));
            if (rate == 1 || sharp > SHARP) why += String.format(java.util.Locale.ROOT, "x%.4f %+.1fs sharp %.1f ratio %.2f lines %ds; ",
                rate, (top - range) * BIN_MS / 1000.0, sharp, second > 0 ? score[top] / second : 99, count[top] / 10);
            boolean odd = rate != 1 && !isPair(rate);   // a stretch off the frame-rate pairs must stand out more (more of them to fit by luck)
            if (count[top] < 150 || score[top] <= 0 || sharp < (odd ? SHARP + 0.5 : SHARP) || score[top] < 1.25 * second) continue;   // 15 s of lines at least
            if (rate == 1) bestOne = sharp;
            if (sharp > (rate == 1 ? bestSharp : Math.max(bestSharp, bestOne + 1))) {
                bestSharp = sharp;
                best = new Fit((long) (top - range) * BIN_MS, rate);
            }
        }
        return best;
    }

    private static boolean isPair(double rate) {
        for (int i = 1; i < 7; i++) if (RATES[i] == rate) return true;
        return false;
    }

    /** The lines' bins at this rate, overlaps merged, only those that can land in [from, to]. */
    private static List<int[]> spans(List<long[]> cues, double rate, int from, int to) {
        List<int[]> all = new ArrayList<>();
        for (long[] c : cues) {
            int a = (int) (c[0] * rate / BIN_MS), b = (int) (c[1] * rate / BIN_MS);
            if (b >= a && b >= from && a <= to) all.add(new int[]{a, b});
        }
        all.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] sp : all) {
            int[] prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && sp[0] <= prev[1] + 1) prev[1] = Math.max(prev[1], sp[1]); else out.add(sp);
        }
        return out;
    }

    /** The file's text with every cue time t moved to t * rate (SubRip and WebVTT timing lines, ASS Dialogue lines), for a file made for another frame rate. */
    static String rescale(String text, double rate) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            boolean ass = line.startsWith("Dialogue:");
            if (ass || line.contains("-->")) {
                Matcher m = TIME.matcher(line);
                StringBuffer b = new StringBuffer();
                for (int left = 2; left > 0 && m.find(); left--) m.appendReplacement(b, Matcher.quoteReplacement(scaled(m, rate)));
                m.appendTail(b);
                line = b.toString();
            }
            out.append(line).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    static final Pattern TIME = Pattern.compile("(?:(\\d+):)?(\\d{2}):(\\d{2})([,.])(\\d{1,3})");

    /** One time, scaled, written the way it was (hours or not and their width, separator, centiseconds or milliseconds). */
    private static String scaled(Matcher m, double rate) {
        String h = m.group(1), frac = m.group(5);
        long ms = ((h == null ? 0 : Long.parseLong(h)) * 3600 + Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3))) * 1000
            + Long.parseLong(frac) * (frac.length() == 1 ? 100 : frac.length() == 2 ? 10 : 1);
        long t = Math.round(ms * rate), hours = t / 3600000;
        String head = h == null && hours == 0 ? "" : String.format(java.util.Locale.ROOT, h != null && h.length() == 1 ? "%d:" : "%02d:", hours);
        String tail = frac.length() == 2 ? String.format(java.util.Locale.ROOT, "%02d", t % 1000 / 10) : String.format(java.util.Locale.ROOT, "%03d", t % 1000);
        return head + String.format(java.util.Locale.ROOT, "%02d:%02d", t / 60000 % 60, t / 1000 % 60) + m.group(4) + tail;
    }
}
