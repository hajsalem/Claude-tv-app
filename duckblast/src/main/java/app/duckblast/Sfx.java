package app.duckblast;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * All game sounds are synthesised at startup (no audio assets), written to the
 * cache as WAV and played through a SoundPool.
 */
final class Sfx {
    private static final int SR = 22050;

    private final SoundPool pool;
    private final Random rnd = new Random(7);
    final int shot, quack, fall, thud, click, fanfare, fail, flap, ding;

    Sfx(Context c) {
        pool = new SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        shot = load(c, "shot", shot());
        quack = load(c, "quack", quack());
        fall = load(c, "fall", fall());
        thud = load(c, "thud", thud());
        click = load(c, "click", click());
        fanfare = load(c, "fanfare", notes(new float[]{523, 659, 784, 1047}, new float[]{.11f, .11f, .11f, .4f}));
        fail = load(c, "fail", notes(new float[]{392, 330, 262, 196}, new float[]{.22f, .22f, .22f, .6f}));
        flap = load(c, "flap", flap());
        ding = load(c, "ding", ding());
    }

    void play(int id, float vol, float rate) {
        if (id != 0) pool.play(id, vol, vol, 1, 0, Math.max(0.5f, Math.min(2f, rate)));
    }

    void play(int id) {
        play(id, 1f, 1f);
    }

    void release() {
        pool.release();
    }

    // ---- synthesis ----

    private float noise() {
        return rnd.nextFloat() * 2f - 1f;
    }

    private float[] shot() {
        int n = (int) (SR * 0.5f);
        float[] s = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            float a = 0.08f + 0.85f * (float) Math.exp(-t * 18); // filter closes as it decays
            lp += a * (noise() - lp);
            float body = lp * (float) Math.exp(-t * 9);
            float crack = t < 0.006f ? noise() : 0;
            float thump = (float) (Math.sin(2 * Math.PI * (70 - 30 * t) * t) * Math.exp(-t * 16)) * 0.9f;
            s[i] = body * 1.4f + crack * 0.8f + thump;
        }
        return s;
    }

    private float[] quack() {
        int n = (int) (SR * 0.32f);
        float[] s = new float[n];
        Biquad bp = Biquad.bandpass(1150, 3.5f);
        Biquad bp2 = Biquad.bandpass(2400, 4f);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            // two syllables: "qua-ack"
            float env;
            float f;
            if (t < 0.09f) {
                env = Math.min(1, t / 0.01f) * (1 - t / 0.09f * 0.5f);
                f = 560 - t * 900;
            } else if (t < 0.11f) {
                env = 0.08f;
                f = 480;
            } else {
                float u = t - 0.11f;
                env = Math.min(1, u / 0.012f) * (float) Math.exp(-u * 11);
                f = 500 - u * 500;
            }
            f *= 1 + 0.03f * (float) Math.sin(2 * Math.PI * 30 * t);
            phase += f / SR;
            float saw = (float) (2 * (phase - Math.floor(phase)) - 1);
            float v = bp.run(saw) * 1.6f + bp2.run(saw) * 0.8f + saw * 0.12f;
            s[i] = v * env;
        }
        return s;
    }

    private float[] fall() {
        int n = (int) (SR * 0.85f);
        float[] s = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            float f = 1500 - 1150 * (t / 0.85f);
            f *= 1 + 0.015f * (float) Math.sin(2 * Math.PI * 7 * t);
            phase += f / SR;
            float env = Math.min(1, t / 0.03f) * (t > 0.75f ? (0.85f - t) / 0.1f : 1);
            s[i] = (float) Math.sin(2 * Math.PI * phase) * env * 0.35f;
        }
        return s;
    }

    private float[] thud() {
        int n = (int) (SR * 0.3f);
        float[] s = new float[n];
        float lp = 0;
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            lp += 0.05f * (noise() - lp);
            phase += (75 - 40 * t / 0.3f) / SR;
            s[i] = ((float) Math.sin(2 * Math.PI * phase) * 0.9f + lp * 2f) * (float) Math.exp(-t * 18);
        }
        return s;
    }

    private float[] click() {
        int n = (int) (SR * 0.05f);
        float[] s = new float[n];
        float prev = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            float x = noise();
            s[i] = (x - prev) * 0.5f * (float) Math.exp(-t * 160)
                    + (float) Math.sin(2 * Math.PI * 1800 * t) * (float) Math.exp(-t * 120) * 0.4f;
            prev = x;
        }
        return s;
    }

    private float[] flap() {
        int n = (int) (SR * 0.45f);
        float[] s = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            lp += 0.25f * (noise() - lp);
            float beat = (t % 0.09f);
            s[i] = lp * (float) Math.exp(-beat * 60) * (1 - t / 0.45f) * 1.5f;
        }
        return s;
    }

    private float[] ding() {
        int n = (int) (SR * 0.25f);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            float f = t < 0.07f ? 1319 : 1760;
            s[i] = (float) Math.sin(2 * Math.PI * f * t) * (float) Math.exp(-(t % 0.07f) * 14) * 0.35f;
        }
        return s;
    }

    private float[] notes(float[] freqs, float[] durs) {
        float total = 0;
        for (float d : durs) total += d;
        float[] s = new float[(int) (SR * total)];
        int at = 0;
        for (int k = 0; k < freqs.length; k++) {
            int len = (int) (SR * durs[k]);
            for (int i = 0; i < len && at + i < s.length; i++) {
                float t = i / (float) SR;
                double ph = freqs[k] * t;
                float sq = (ph - Math.floor(ph)) < 0.5 ? 1 : -1;
                float tri = (float) (Math.abs(4 * (ph - Math.floor(ph + 0.5))) - 1);
                float env = Math.min(1, t / 0.008f) * Math.max(0, 1 - t / durs[k] * 0.7f);
                s[at + i] = (sq * 0.18f + tri * 0.4f) * env;
            }
            at += len;
        }
        return s;
    }

    // ---- file plumbing ----

    private int load(Context c, String name, float[] samples) {
        try {
            File f = new File(c.getCacheDir(), "sfx_" + name + ".wav");
            writeWav(f, samples);
            return pool.load(f.getPath(), 1);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void writeWav(File f, float[] s) throws IOException {
        float peak = 0.0001f;
        for (float v : s) peak = Math.max(peak, Math.abs(v));
        float gain = 0.9f / peak;
        byte[] data = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            int v = Math.round(Math.max(-1, Math.min(1, s[i] * gain)) * 32767);
            data[2 * i] = (byte) v;
            data[2 * i + 1] = (byte) (v >> 8);
        }
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        le32(h, 4, 36 + data.length);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        le32(h, 16, 16);
        h[20] = 1; // PCM
        h[22] = 1; // mono
        le32(h, 24, SR);
        le32(h, 28, SR * 2);
        h[32] = 2;
        h[34] = 16;
        put(h, 36, "data");
        le32(h, 40, data.length);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(h);
            out.write(data);
        }
    }

    private static void put(byte[] b, int off, String s) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    /** RBJ cookbook band-pass filter. */
    private static final class Biquad {
        float b0, b1, b2, a1, a2, x1, x2, y1, y2;

        static Biquad bandpass(float f, float q) {
            Biquad b = new Biquad();
            double w = 2 * Math.PI * f / SR;
            double alpha = Math.sin(w) / (2 * q);
            double a0 = 1 + alpha;
            b.b0 = (float) (alpha / a0);
            b.b1 = 0;
            b.b2 = (float) (-alpha / a0);
            b.a1 = (float) (-2 * Math.cos(w) / a0);
            b.a2 = (float) ((1 - alpha) / a0);
            return b;
        }

        float run(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }
}
