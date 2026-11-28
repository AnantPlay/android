package me.champdev.weblock;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Sits in the player's sound path and passes the sound through untouched, noting how much speech there is in every 0.1 s of the
 * video (for SubSync): the energy of the voice band (300-3400 Hz) times how voiced it is (the best autocorrelation at a voice's pitch,
 * 80-400 Hz, squared), so music beds, effects and noise count for little. Up to 4 hours; written on the playback thread, read on any.
 * (Tested against plain loudness on real films: four times as many confident answers, none wrong.)
 */
final class SpeechMeter extends BaseAudioProcessor {
    final float[] level = new float[4 * 3600 * 1000 / SubSync.BIN_MS];
    private long baseUs, frames;
    private double band;
    private int accFrames;
    // voice band: two high-pass and two low-pass biquads; pitch band: two low-pass at 900 Hz, kept every `step`-th sample (about 16 kHz)
    private final double[][] bq = new double[6][7];   // b0 b1 b2 a1 a2 z1 z2
    private float[] low = new float[2048];
    private int lows, step, minLag, maxLag, skip;

    SpeechMeter() {
        Arrays.fill(level, -1);
    }

    @Override protected AudioFormat onConfigure(AudioFormat in) {
        if (in.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET;   // other formats pass by without a meter
        double fs = in.sampleRate;
        set(bq[0], fs, 300, true); set(bq[1], fs, 300, true); set(bq[2], fs, 3400, false); set(bq[3], fs, 3400, false);
        set(bq[4], fs, 900, false); set(bq[5], fs, 900, false);
        step = Math.max(1, (int) Math.round(fs / 16000));
        double ds = fs / step;
        minLag = (int) (ds / 400);
        maxLag = (int) (ds / 80);
        low = new float[(int) (ds * SubSync.BIN_MS / 1000) + 8];
        return in;
    }

    /** A Butterworth-ish biquad (Q 0.707) at fc: high-pass or low-pass. */
    private static void set(double[] q, double fs, double fc, boolean high) {
        double w = 2 * Math.PI * fc / fs, c = Math.cos(w), al = Math.sin(w) / Math.sqrt(2), a0 = 1 + al;
        double b0 = high ? (1 + c) / 2 : (1 - c) / 2, b1 = high ? -(1 + c) : 1 - c;
        q[0] = b0 / a0; q[1] = b1 / a0; q[2] = b0 / a0; q[3] = -2 * c / a0; q[4] = (1 - al) / a0; q[5] = 0; q[6] = 0;
    }

    private static double run(double[] q, double x) {
        double y = q[0] * x + q[5];
        q[5] = q[1] * x - q[3] * y + q[6];
        q[6] = q[2] * x - q[4] * y;
        return y;
    }

    @Override protected void onFlush(AudioProcessor.StreamMetadata m) {
        baseUs = m.positionOffsetUs;   // where the sound continues from (a seek, a new start)
        frames = 0;
        band = 0;
        accFrames = 0;
        lows = 0;
    }

    @Override public void queueInput(ByteBuffer in) {
        if (!in.hasRemaining()) return;   // an empty buffer is the shared EMPTY_BUFFER, as our own output would be: copying it onto itself throws
        int ch = inputAudioFormat.channelCount, rate = inputAudioFormat.sampleRate, binFrames = rate * SubSync.BIN_MS / 1000;
        ByteBuffer src = in.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        while (src.remaining() >= 2 * ch) {
            int mix = 0;
            for (int c = 0; c < ch; c++) mix += src.getShort();
            double x = mix / (32768.0 * ch);
            double b = run(bq[3], run(bq[2], run(bq[1], run(bq[0], x))));
            band += b * b;
            double lo = run(bq[5], run(bq[4], x));
            if (++skip >= step) { skip = 0; if (lows < low.length) low[lows++] = (float) lo; }
            if (++accFrames == binFrames) {
                long bin = (baseUs / 1000 + frames * 1000 / rate) / SubSync.BIN_MS;
                double v = voicing();
                if (bin >= 0 && bin < level.length) level[(int) bin] = (float) (band / accFrames * v * v);
                frames += accFrames;
                band = 0;
                accFrames = 0;
                lows = 0;
            }
        }
        int n = in.remaining();
        ByteBuffer out = replaceOutputBuffer(n);
        out.put(in);
        out.flip();
    }

    /** How voiced the bin's low band is, 0-1: the best normalized autocorrelation at a pitch lag, the mean of its two halves (every other lag: cheap enough for a TV). */
    private double voicing() {
        double v = 0;
        int half = lows / 2;
        for (int h = 0; h < 2; h++) {
            int s0 = h * half, len = half - maxLag;
            if (len < 16) return 0;
            double r0 = 0, best = 0;
            for (int i = s0; i < s0 + len; i++) r0 += low[i] * low[i];
            if (r0 < 1e-12) continue;
            for (int lag = minLag; lag <= maxLag; lag += 2) {
                double r = 0, rl = 0;
                for (int i = s0; i < s0 + len; i++) { float y = low[i + lag]; r += low[i] * y; rl += y * y; }
                double c = r / Math.sqrt(r0 * rl + 1e-18);
                if (c > best) best = c;
            }
            v += best / 2;
        }
        return v;
    }

    /** A copy of what was measured, for SubSync on another thread. */
    float[] snapshot() {
        return level.clone();
    }
}
