package com.laurentcarrie.zik;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Drum loop synthesised on the device (rock, funk, shuffle), played in a gapless loop. */
final class DrumMachine {
    static final String[] STYLES = {"rock", "funk", "shuffle"};
    private static final int RATE = 44100;
    static final int BARS = 4;
    private static final int KICK = 0, SNARE = 1, HAT = 2, OPEN = 3, TOM_HI = 4, TOM_MID = 5, TOM_LO = 6, CRASH = 7;

    private final short[][] samples = new short[8][];
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioTrack track;
    private volatile int loopFrames, framesPerBeat;
    private boolean playing;
    private int generation; // bumped on each start / stop, so a stale background render is dropped

    private final android.content.Context context;
    private boolean samplesReady;
    private int bpm = 100, style;
    private static DrumMachine instance;

    private DrumMachine(android.content.Context context) {
        this.context = context.getApplicationContext();
    }

    /** One machine for the whole app, so the loop goes on while you move between pages. */
    static synchronized DrumMachine get(android.content.Context context) {
        if (instance == null) instance = new DrumMachine(context);
        return instance;
    }

    /** Tempo and style of the loop being played, or of the last one played. */
    int getBpm() { return bpm; }
    int getStyle() { return style; }

    /** Level of each recorded sample, so that the kit is balanced (the files are peak-normalised). */
    private static final float[] LEVEL = {1.0f, 0.9f, 0.55f, 0.5f, 0.9f, 0.9f, 0.9f, 0.6f};
    private static final String[] FILES = {"kick", "snare", "hat", "open", "tom_hi", "tom_mid", "tom_lo", "crash"};

    /** Loads the recorded samples from the assets; an instrument whose file is missing keeps its synthesised sound. */
    private synchronized void ensureSamples() {
        if (samplesReady) return;
        synthesizeSamples();
        int loaded = 0;
        for (int i = 0; i < FILES.length; i++) {
            try (java.io.InputStream in = context.getAssets().open("drums/" + FILES[i] + ".wav")) {
                samples[i] = readWav(in, LEVEL[i]);
                loaded++;
            } catch (Exception ignored) { /* keep the synthesised sound */ }
        }
        android.util.Log.i("DrumMachine", "recorded samples loaded: " + loaded + " / " + FILES.length);
        samplesReady = true;
    }

    /** 16-bit mono PCM WAV (44.1 kHz) to samples, scaled by level. */
    private static short[] readWav(java.io.InputStream in, float level) throws java.io.IOException {
        byte[] b = new byte[Math.max(1, in.available())];
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(b)) > 0) all.write(b, 0, n);
        byte[] d = all.toByteArray();
        int pos = 12; // after "RIFF....WAVE"
        while (pos + 8 <= d.length) {
            int size = (d[pos + 4] & 0xFF) | (d[pos + 5] & 0xFF) << 8 | (d[pos + 6] & 0xFF) << 16 | (d[pos + 7] & 0xFF) << 24;
            if (d[pos] == 'd' && d[pos + 1] == 'a' && d[pos + 2] == 't' && d[pos + 3] == 'a') {
                int frames = Math.min(size, d.length - pos - 8) / 2;
                short[] s = new short[frames];
                for (int i = 0; i < frames; i++) {
                    int v = (short) ((d[pos + 8 + 2 * i] & 0xFF) | (d[pos + 9 + 2 * i] << 8));
                    s[i] = (short) Math.max(-32768, Math.min(32767, v * level));
                }
                return s;
            }
            pos += 8 + size + (size & 1);
        }
        throw new java.io.IOException("no data chunk");
    }

    boolean isPlaying() {
        return playing;
    }

    /** Renders the loop in the background, then plays it. Calling it again restarts at the new tempo / style. */
    void start(int bpm, int style) {
        this.bpm = bpm;
        this.style = style;
        stopTrack();
        loops = 0;
        lastPos = 0;
        playing = true;
        final int gen = ++generation;
        new Thread(() -> {
            ensureSamples();
            short[] pcm = render(bpm, style);
            main.post(() -> {
                if (gen != generation || !playing) return;
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                loopFrames = pcm.length;
                framesPerBeat = pcm.length / (BARS * 4);
                t.write(pcm, 0, pcm.length);
                t.setLoopPoints(0, pcm.length, -1);
                t.play();
                track = t;
            });
        }).start();
    }

    /** Where the loop is now: {bar (0-3), beat (0-3)}, or null when nothing is playing. */
    int[] position() {
        AudioTrack t = track;
        int total = loopFrames, fpb = framesPerBeat;
        if (!playing || t == null || total <= 0 || fpb <= 0) return null;
        try {
            long pos = (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) % total;
            int beatIndex = (int) Math.min(pos / fpb, BARS * 4 - 1);
            return new int[]{beatIndex / 4, beatIndex % 4};
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private long loops, lastPos;

    /** Bars played since the loop started (counting through the loops), or -1 when nothing is playing. Call it often enough to see each wrap of the loop. */
    long barsPlayed() {
        AudioTrack t = track;
        int total = loopFrames, fpb = framesPerBeat;
        if (!playing || t == null || total <= 0 || fpb <= 0) return -1;
        try {
            long pos = (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) % total;
            if (pos < lastPos) loops++;
            lastPos = pos;
            return (loops * total + pos) / ((long) fpb * 4);
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    void stop() {
        generation++;
        playing = false;
        stopTrack();
    }

    private void stopTrack() {
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
            track = null;
        }
    }

    // ---- patterns: [position, instrument, velocity], 16 or 12 steps per bar ----

    private static List<int[]> pattern(int style, boolean last) {
        List<int[]> n = new ArrayList<>();
        if (style == 2) { // shuffle: 12 steps, 3 per beat
            for (int beat = 0; beat < 4; beat++) {
                n.add(new int[]{beat * 3, HAT, beat % 2 == 0 ? 90 : 75});
                n.add(new int[]{beat * 3 + 2, HAT, 60});
            }
            n.add(new int[]{0, KICK, 110});
            n.add(new int[]{6, KICK, 100});
            n.add(new int[]{3, SNARE, 105});
            n.add(new int[]{9, SNARE, 105});
            if (last) {
                dropFrom(n, 9);
                n.add(new int[]{9, TOM_HI, 100});
                n.add(new int[]{10, TOM_MID, 100});
                n.add(new int[]{11, TOM_LO, 105});
            }
            return n;
        }
        if (style == 1) { // funk: 16 steps
            for (int i = 0; i < 16; i++) n.add(new int[]{i, HAT, i % 4 == 0 ? 95 : i % 2 == 0 ? 70 : 50});
            int[][] k = {{0, 115}, {3, 95}, {6, 100}, {10, 105}, {11, 90}};
            for (int[] x : k) n.add(new int[]{x[0], KICK, x[1]});
            int[][] sn = {{4, 115}, {12, 115}, {7, 45}, {9, 45}, {15, 55}};
            for (int[] x : sn) n.add(new int[]{x[0], SNARE, x[1]});
            if (last) {
                dropFrom(n, 12);
                n.add(new int[]{12, TOM_HI, 105});
                n.add(new int[]{13, TOM_MID, 105});
                n.add(new int[]{14, TOM_LO, 110});
                n.add(new int[]{15, TOM_LO, 115});
            }
            return n;
        }
        for (int i = 0; i < 16; i += 2) n.add(new int[]{i, HAT, i % 4 == 0 ? 95 : 75}); // rock
        n.add(new int[]{0, KICK, 115});
        n.add(new int[]{8, KICK, 110});
        n.add(new int[]{10, KICK, 100});
        n.add(new int[]{4, SNARE, 115});
        n.add(new int[]{12, SNARE, 115});
        if (last) {
            dropFrom(n, 12);
            n.add(new int[]{12, SNARE, 105});
            n.add(new int[]{13, TOM_HI, 100});
            n.add(new int[]{14, TOM_MID, 105});
            n.add(new int[]{15, TOM_LO, 110});
        }
        return n;
    }

    private static void dropFrom(List<int[]> n, int pos) {
        for (int i = n.size() - 1; i >= 0; i--) if (n.get(i)[0] >= pos) n.remove(i);
    }

    /** BARS bars of 4/4 at the given tempo and style, a crash on the first beat and a fill in the last bar. */
    private short[] render(int bpm, int style) {
        int barFrames = (int) Math.round(4 * 60.0 / bpm * RATE);
        int total = barFrames * BARS;
        float[] mix = new float[total];
        for (int bar = 0; bar < BARS; bar++) {
            boolean last = bar == BARS - 1;
            List<int[]> notes = pattern(style, last);
            int steps = style == 2 ? 12 : 16;
            if (bar == 0) notes.add(new int[]{0, CRASH, 110});
            for (int[] note : notes) {
                int at = bar * barFrames + (int) ((long) note[0] * barFrames / steps);
                short[] smp = samples[note[1]];
                float gain = note[2] / 127f;
                for (int i = 0; i < smp.length; i++) {
                    int j = (at + i) % total; // tails wrap around, so the loop has no gap
                    mix[j] += smp[i] * gain;
                }
            }
        }
        short[] out = new short[total];
        for (int i = 0; i < total; i++) {
            float v = mix[i] * 0.6f;
            out[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return out;
    }

    // ---- drum sounds, synthesised ----

    private void synthesizeSamples() {
        Random rnd = new Random(1);
        samples[KICK] = tone(0.30, 130, 45, 0.09, 0.0, rnd);
        samples[SNARE] = mixSamples(noise(0.20, 0.06, false, rnd), tone(0.12, 200, 160, 0.04, 0.0, rnd), 0.8f, 0.6f);
        samples[HAT] = noise(0.06, 0.012, true, rnd);
        samples[OPEN] = noise(0.35, 0.09, true, rnd);
        samples[TOM_HI] = tone(0.30, 220, 150, 0.12, 0.0, rnd);
        samples[TOM_MID] = tone(0.35, 165, 110, 0.14, 0.0, rnd);
        samples[TOM_LO] = tone(0.40, 120, 80, 0.16, 0.0, rnd);
        samples[CRASH] = noise(1.2, 0.35, true, rnd);
    }

    /** Sine with a pitch drop from f0 to f1 and an exponential decay. */
    private static short[] tone(double seconds, double f0, double f1, double decay, double unused, Random rnd) {
        int n = (int) (seconds * RATE);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double f = f1 + (f0 - f1) * Math.exp(-t / 0.03);
            phase += 2 * Math.PI * f / RATE;
            s[i] = (short) (Math.sin(phase) * Math.exp(-t / decay) * 28000);
        }
        return s;
    }

    /** White noise with an exponential decay; the high-passed version is brighter (hats, cymbals). */
    private static short[] noise(double seconds, double decay, boolean bright, Random rnd) {
        int n = (int) (seconds * RATE);
        short[] s = new short[n];
        double prev = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double x = rnd.nextDouble() * 2 - 1;
            double v = bright ? x - prev : x; // first difference = simple high-pass
            prev = x;
            s[i] = (short) (v * Math.exp(-t / decay) * (bright ? 12000 : 20000));
        }
        return s;
    }

    private static short[] mixSamples(short[] a, short[] b, float ga, float gb) {
        short[] s = new short[Math.max(a.length, b.length)];
        for (int i = 0; i < s.length; i++) {
            float v = (i < a.length ? a[i] * ga : 0) + (i < b.length ? b[i] * gb : 0);
            s[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return s;
    }
}
