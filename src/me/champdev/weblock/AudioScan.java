package me.champdev.weblock;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * Fast subtitle sync: a few minutes of the stream's sound fetched and decoded as fast as the network and the decoder go (not as it
 * plays), measured by a SpeechMeter of its own. HLS: the sound's own playlist when there is one, else the lightest picture; DASH: the
 * sound track; a plain file: read in place. Live, protected and one-file DASH streams are skipped (the player's own listening goes on).
 */
final class AudioScan {
    static final long MAX_BYTES = 80L << 20;   // a stream with one heavy quality and its sound inside: stop at 80 MB

    /** Measures [fromMs, toMs) of the stream into `meter`; false when this stream can't be read this way. Off the main thread; `stop` ends it early. */
    static boolean scan(String url, String headers, boolean dash, long fromMs, long toMs, SpeechMeter meter, BooleanSupplier stop) {
        Downloads.Item look = new Downloads.Item();
        look.url = url;
        look.headers = headers == null || headers.isEmpty() ? "{}" : headers;
        try {
            String text = Downloads.fetchText(look, url);
            if (dash || text.contains("<MPD")) {
                Downloads.Dash.Pick p = Downloads.Dash.pick(text, url, Downloads.audioLang());
                Downloads.Track t = p.audio != null ? p.audio : p.video;
                return !t.whole && segments(look, t, fromMs, toMs, meter, stop);
            }
            if (!text.startsWith("#EXTM3U")) return decode(url, look.headers, fromMs * 1000, toMs * 1000, meter, stop, true);
            String base = url;
            if (text.contains("#EXT-X-STREAM-INF")) {
                String[] v = lightest(text);
                if (v == null) return false;
                String au = Downloads.audioUri(text, v[1]);
                base = Downloads.Hls.resolve(base, au != null ? au : v[0]);
                text = Downloads.fetchText(look, base);
            }
            return segments(look, Downloads.Hls.track(text, base), fromMs, toMs, meter, stop);
        } catch (Exception e) {
            Log.w("SubSync", "scan: " + e);
            return false;
        }
    }

    /** {address, audio group} of the lowest-bandwidth variant of a main playlist; null when there is none. */
    static String[] lightest(String text) {
        String[] lines = text.split("\\r?\\n"), out = null;
        long low = Long.MAX_VALUE;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            long bw = Long.MAX_VALUE - 1;
            try { bw = Long.parseLong(String.valueOf(Downloads.Hls.attr(lines[i], "BANDWIDTH")).trim()); } catch (NumberFormatException ignored) { }
            String uri = null;
            for (int k = i + 1; k < lines.length && uri == null; k++) if (!lines[k].trim().isEmpty() && !lines[k].startsWith("#")) uri = lines[k].trim();
            if (uri != null && bw < low) { low = bw; out = new String[]{uri, Downloads.Hls.attr(lines[i], "AUDIO")}; }
        }
        return out;
    }

    /** The pieces covering [fromMs, toMs), four fetched at a time, each decoded from where the playlist says it starts. */
    private static boolean segments(Downloads.Item look, Downloads.Track t, long fromMs, long toMs, SpeechMeter meter, BooleanSupplier stop) throws Exception {
        byte[] init = t.map == null ? null : Downloads.get(look, t.map, t.mapFrom, t.mapLen);
        List<Downloads.Seg> all = t.segs;
        java.util.List<Downloads.Seg> want = new java.util.ArrayList<>();
        java.util.List<Long> starts = new java.util.ArrayList<>();
        double at = 0;
        for (Downloads.Seg s : all) {
            double start = at;
            at += s.dur;
            if (at * 1000 <= fromMs) continue;
            if (start * 1000 >= toMs) break;
            want.add(s);
            starts.add((long) (start * 1e6));
        }
        if (want.isEmpty()) return false;
        Map<String, byte[]> keys = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        long bytes = 0;
        try {
            ArrayDeque<Future<byte[]>> ahead = new ArrayDeque<>();
            int next = 0;
            for (int i = 0; i < want.size(); i++) {
                while (next < want.size() && ahead.size() < 6) {
                    final Downloads.Seg s = want.get(next++);
                    ahead.add(pool.submit(() -> Downloads.segment(look, s, keys)));
                }
                byte[] d = ahead.poll().get();
                if (stop.getAsBoolean() || (bytes += d.length) > MAX_BYTES) break;
                File f = File.createTempFile("scan", ".seg", App.app.getCacheDir());
                try {
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        if (init != null) o.write(init);
                        o.write(d);
                    }
                    decode(f.getPath(), null, starts.get(i), Long.MAX_VALUE, meter, stop, false);
                } finally {
                    f.delete();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return true;
    }

    /**
     * Decodes the sound of a file (a piece, or a whole stream when `seek`) into the meter. A piece: its samples are timed from `startUs`
     * (where the playlist puts it), whatever its own timestamps say. A whole stream: read from startUs to endUs at its own times.
     */
    private static boolean decode(String source, String headers, long startUs, long endUs, SpeechMeter meter, BooleanSupplier stop, boolean seek) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            if (headers == null) ex.setDataSource(source);
            else {
                Map<String, String> h = new HashMap<>();
                org.json.JSONObject o = new org.json.JSONObject(headers);
                for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) { String key = k.next(); h.put(key, o.getString(key)); }
                ex.setDataSource(source, h);
            }
            int track = -1;
            for (int i = 0; i < ex.getTrackCount() && track < 0; i++) if (String.valueOf(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)).startsWith("audio/")) track = i;
            if (track < 0) return false;
            ex.selectTrack(track);
            if (seek) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            MediaFormat in = ex.getTrackFormat(track);
            codec = MediaCodec.createDecoderByType(in.getString(MediaFormat.KEY_MIME));
            codec.configure(in, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long first = -1;
            boolean inDone = false, configured = false, isFloat = false;
            int channels = 2, rate = 48000;
            while (!stop.getAsBoolean()) {
                if (!inDone) {
                    int i = codec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        int n = ex.readSampleData(codec.getInputBuffer(i), 0);
                        long ts = ex.getSampleTime();
                        if (n < 0 || seek && ts > endUs) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { codec.queueInputBuffer(i, 0, n, ts, 0); ex.advance(); }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT;
                    configured = false;
                } else if (o >= 0) {
                    if (info.size > 0) {
                        if (first < 0) first = info.presentationTimeUs;
                        if (!configured) {   // timed from the first sample out: the piece's place in the playlist, or the stream's own time
                            meter.configure(new AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT));
                            meter.flush(new AudioProcessor.StreamMetadata(seek ? info.presentationTimeUs : startUs + info.presentationTimeUs - first));
                            configured = true;
                        }
                        ByteBuffer pcm = codec.getOutputBuffer(o);
                        pcm.position(info.offset).limit(info.offset + info.size);
                        meter.queueInput(isFloat ? to16(pcm) : pcm);
                        meter.getOutput();
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            return true;
        } finally {
            if (codec != null) try { codec.stop(); codec.release(); } catch (RuntimeException ignored) { }
            ex.release();
        }
    }

    private static ByteBuffer to16(ByteBuffer f) {
        java.nio.FloatBuffer fl = f.order(ByteOrder.nativeOrder()).asFloatBuffer();
        ByteBuffer out = ByteBuffer.allocateDirect(fl.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
        while (fl.hasRemaining()) out.putShort((short) Math.max(-32768, Math.min(32767, fl.get() * 32767)));
        out.flip();
        return out;
    }

    /** Where to listen: the start (ms, file time) of the 3 minutes with the most lines among the next 15 minutes of the file from `fromMs`. */
    static long densest(List<long[]> cues, long fromMs) {
        long best = fromMs;
        int most = -1;
        for (long s = fromMs; s <= fromMs + 12 * 60_000; s += 15_000) {
            int n = 0;
            for (long[] c : cues) if (c[0] >= s && c[0] < s + 180_000) n++;
            if (n > most) { most = n; best = s; }
        }
        return best;
    }
}
