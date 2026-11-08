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
    private static final int KICK = 0, SNARE = 1, HAT = 2, OPEN = 3, TOM_HI = 4, TOM_MID = 5, TOM_LO = 6, CRASH = 7, STICK = 8;

    private final short[][] samples = new short[9][];
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioTrack track, chordTrack; // the drums; the chords of the song, when it has them
    private volatile float drumVolume = 1f, chordVolume = 1f;

    /** The instruments of the chords (rendered from the soundfont, see tools/make_chord_samples.py): names shown, asset folders. */
    static final String[] INSTRUMENTS = {"Piano", "Piano électrique", "Guitare nylon", "Guitare acier"};
    private static final String[] INSTRUMENT_DIRS = {"piano", "epiano", "nylon", "steel"};
    private static final int[] KIT_NOTES = {36, 42, 48, 54, 60, 66, 72}; // the MIDI notes sampled
    private volatile int chordInstrument;
    private final java.util.Map<Integer, short[][]> kits = new java.util.HashMap<>();

    /** The instrument of the chords, taken into account by the next start. */
    void setChordInstrument(int i) { chordInstrument = Math.max(0, Math.min(INSTRUMENTS.length - 1, i)); }
    int getChordInstrument() { return chordInstrument; }

    /** The sampled notes of an instrument (one per KIT_NOTES), or null when its files are missing. */
    private synchronized short[][] kit(int instrument) {
        if (kits.containsKey(instrument)) return kits.get(instrument);
        short[][] k = new short[KIT_NOTES.length][];
        try {
            for (int i = 0; i < KIT_NOTES.length; i++) {
                try (java.io.InputStream in = context.getAssets().open("chords/" + INSTRUMENT_DIRS[instrument] + "/" + KIT_NOTES[i] + ".wav")) {
                    k[i] = readWav(in, 1f);
                }
            }
        } catch (Exception e) {
            android.util.Log.w("DrumMachine", "chord samples missing: " + e);
            k = null; // the additive synth is used instead
        }
        kits.put(instrument, k);
        return k;
    }
    private volatile int loopFrames, framesPerBeat, loopBars = BARS;
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
    /** Bars of the loop being played: BARS, or the number of bars of the song when it plays the chords. */
    int getLoopBars() { return loopBars; }

    /** Level (0 to 1) of the drums and of the chords, applied at once to what is playing. */
    void setVolumes(float drums, float chords) {
        drumVolume = Math.max(0f, Math.min(1f, drums));
        chordVolume = Math.max(0f, Math.min(1f, chords));
        try {
            if (track != null) track.setVolume(drumVolume);
            if (chordTrack != null) chordTrack.setVolume(chordVolume);
        } catch (IllegalStateException ignored) { }
    }

    /** Level of each recorded sample, so that the kit is balanced (the files are peak-normalised). */
    private static final float[] LEVEL = {1.0f, 0.9f, 0.55f, 0.5f, 0.9f, 0.9f, 0.9f, 0.6f, 0.8f};
    private static final String[] FILES = {"kick", "snare", "hat", "open", "tom_hi", "tom_mid", "tom_lo", "crash", "stick"};

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
        start(bpm, style, null, 0, BARS - 1, 0, false, true);
    }

    /**
     * With chords (one string per bar of the song, see BarCells.chords) plays the bars from..to (0-based,
     * included) of the song, drums and chords; without, BARS bars of drums. With countIn a bar of four
     * stick beats comes in front of the range, and again at each turn of the loop. The range loops when
     * loop is set, else it plays once and finished() becomes true. offsetBars is where in the range it
     * starts; -1 starts on the count-in bar (needs countIn).
     */
    void start(int bpm, int style, String[] chords, int from, int to, int offsetBars, boolean countIn, boolean loop) {
        this.bpm = bpm;
        this.style = style;
        stopTrack();
        playing = true;
        final int gen = ++generation;
        final String[] songChords = chords == null || chords.length == 0 ? null : chords;
        final int first = songChords == null ? 0 : Math.max(0, Math.min(from, songChords.length - 1));
        final int last = songChords == null ? BARS - 1 : Math.max(first, Math.min(to, songChords.length - 1));
        final int bars = last - first + 1;
        new Thread(() -> {
            ensureSamples();
            short[][] pcm = render(bpm, style, first, bars, songChords, songChords == null ? null : kit(chordInstrument), countIn);
            main.post(() -> {
                if (gen != generation || !playing) return;
                int rangeLen = pcm[0].length - (countIn ? barFramesOf(bpm) : 0);
                loopBars = bars;
                framesPerBeat = rangeLen / (bars * 4);
                countInFrames = countIn ? barFramesOf(bpm) : 0;
                loopFrames = rangeLen;
                rangeLoop = loop;
                int head = countIn && offsetBars < 0 ? 0
                        : countInFrames + Math.max(0, Math.min(offsetBars, bars - 1)) * framesPerBeat * 4;
                // two tracks, one per part, so that each has its own level; started one after the other
                AudioTrack d = makeTrack(pcm[0], head, 0, loop, drumVolume);
                AudioTrack c = pcm[1] == null ? null : makeTrack(pcm[1], head, 0, loop, chordVolume);
                if (c != null) c.play();
                d.play();
                chordTrack = c;
                track = d;
            });
        }).start();
    }

    private static int barFramesOf(int bpm) {
        return (int) Math.round(4 * 60.0 / bpm * RATE);
    }

    /** A static track of pcm, ready at frame `head`, at the given level; looping from loopStart to the end when loop is set. */
    private static AudioTrack makeTrack(short[] pcm, int head, int loopStart, boolean loop, float volume) {
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
        t.write(pcm, 0, pcm.length);
        if (loop) t.setLoopPoints(loopStart, pcm.length, -1);
        if (head > 0) t.setPlaybackHeadPosition(head);
        t.setVolume(volume);
        return t;
    }

    private volatile int countInFrames;
    private volatile boolean rangeLoop = true;

    /** Loop on or off for what is playing: the range goes round, or plays to its end. */
    void setLoop(boolean loop) {
        rangeLoop = loop;
        int total = countInFrames + loopFrames;
        try {
            for (AudioTrack t : new AudioTrack[]{track, chordTrack}) {
                if (t == null) continue;
                if (loop) t.setLoopPoints(0, total, -1);
                else t.setLoopPoints(0, 0, 0);
            }
        } catch (IllegalStateException | IllegalArgumentException ignored) { }
    }

    /** A range that does not loop has played to its end. */
    boolean finished() {
        AudioTrack t = track;
        if (!playing || t == null || rangeLoop || loopFrames <= 0) return false;
        try {
            return (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) >= countInFrames + loopFrames - 2;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Where the loop is now: {bar of the range (-1 during the count-in), beat (0-3)}, or null when nothing is playing. */
    int[] position() {
        AudioTrack t = track;
        int total = loopFrames, fpb = framesPerBeat, cin = countInFrames;
        if (!playing || t == null || total <= 0 || fpb <= 0) return null;
        try {
            long head = (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) % (cin + total); // the loop goes round the count-in too
            if (head < cin) return new int[]{-1, (int) Math.min(head / fpb, 3)};
            long pos = head - cin;
            int beatIndex = (int) Math.min(pos / fpb, loopBars * 4 - 1);
            return new int[]{beatIndex / 4, beatIndex % 4};
        } catch (IllegalStateException e) {
            return null;
        }
    }

    void stop() {
        generation++;
        playing = false;
        stopTrack();
    }

    private void stopTrack() {
        for (AudioTrack t : new AudioTrack[]{track, chordTrack}) {
            if (t == null) continue;
            try { t.stop(); } catch (IllegalStateException ignored) { }
            t.release();
        }
        track = null;
        chordTrack = null;
    }

    /** The loop being played has the chords of the song on a track of their own. */
    boolean hasChords() {
        return playing && chordTrack != null;
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

    /** `bars` bars of 4/4 at the given tempo and style, a crash on the first beat, a fill in the last bar of every 4, and the chords of each bar when given. */
    private short[][] render(int bpm, int style, int first, int bars, String[] chords, short[][] kit, boolean countIn) {
        int barFrames = barFramesOf(bpm);
        int total = barFrames * bars;
        int songBars = chords == null ? BARS : chords.length;
        float[] mix = new float[total];
        float[] chordMix = chords == null ? null : new float[total];
        long t0 = System.nanoTime();
        java.util.Map<String, float[]> notesCache = new java.util.HashMap<>(); // the same notes come back bar after bar
        for (int k = 0; k < bars; k++) {
            int bar = first + k; // bar of the song
            boolean last = bar % 4 == 3 || bar == songBars - 1 || k == bars - 1; // a fill ends each phrase, and the range
            List<int[]> notes = pattern(style, last);
            int steps = style == 2 ? 12 : 16;
            if (k == 0) notes.add(new int[]{0, CRASH, 110});
            for (int[] note : notes) {
                int at = k * barFrames + (int) ((long) note[0] * barFrames / steps);
                short[] smp = samples[note[1]];
                float gain = note[2] / 127f;
                for (int i = 0; i < smp.length; i++) {
                    int j = (at + i) % total; // tails wrap around the range, so the loop has no gap
                    mix[j] += smp[i] * gain;
                }
            }
            if (chordMix != null) addChords(chordMix, k * barFrames, barFrames, chords[bar], notesCache, kit);
        }
        android.util.Log.i("DrumMachine", "rendered " + bars + " bars in " + (System.nanoTime() - t0) / 1000000 + " ms");
        short[] drumsPcm = toPcm(mix), chordsPcm = chordMix == null ? null : toPcm(chordMix);
        if (countIn) { // a bar of four stick beats in front, the first one stronger
            short[] cin = new short[barFrames];
            short[] st = samples[STICK];
            for (int beat = 0; beat < 4; beat++) {
                float gain = (beat == 0 ? 1.0f : 0.75f) * 0.6f;
                int at = beat * (barFrames / 4);
                for (int i = 0; i < st.length && at + i < cin.length; i++) {
                    cin[at + i] = (short) Math.max(-32768, Math.min(32767, cin[at + i] + st[i] * gain));
                }
            }
            drumsPcm = concat(cin, drumsPcm);
            if (chordsPcm != null) chordsPcm = concat(new short[barFrames], chordsPcm);
        }
        return new short[][]{drumsPcm, chordsPcm};
    }

    private static short[] concat(short[] a, short[] b) {
        short[] out = new short[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static short[] toPcm(float[] mix) {
        short[] out = new short[mix.length];
        for (int i = 0; i < mix.length; i++) {
            float v = mix[i] * 0.6f;
            out[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return out;
    }

    // ---- chords ----

    private static int pitchClass(char c) {
        switch (c) {
            case 'C': return 0;
            case 'D': return 2;
            case 'E': return 4;
            case 'F': return 5;
            case 'G': return 7;
            case 'A': return 9;
            default: return 11; // B
        }
    }

    /** Root pitch class (0-11) of a chord token ("Fxm7": F sharp minor 7; x sharp, b flat) and the length of the root. */
    private static int rootOf(String t, int[] len) {
        int pc = pitchClass(t.charAt(0));
        len[0] = 1;
        if (t.length() > 1 && t.charAt(1) == 'x') { pc++; len[0] = 2; }
        else if (t.length() > 1 && t.charAt(1) == 'b') { pc--; len[0] = 2; }
        return (pc + 12) % 12;
    }

    /** MIDI notes of a chord token: {bass, then the chord above}; null for a rest or an unreadable token. */
    static int[] chordNotes(String token) {
        if (token.isEmpty() || token.equals("r") || token.charAt(0) < 'A' || token.charAt(0) > 'G') return null;
        String slash = null;
        int sl = token.indexOf('/');
        if (sl > 0) { slash = token.substring(sl + 1); token = token.substring(0, sl); }
        int[] len = new int[1];
        int root = rootOf(token, len);
        String q = token.substring(len[0]);
        boolean minor = q.startsWith("m");
        if (minor) q = q.substring(1);
        List<Integer> n = new ArrayList<>();
        int base = 48 + root; // C3 and up
        n.add(base);
        boolean fifth = true;
        if (q.equals("sus2")) n.add(base + 2);
        else if (q.equals("sus4")) n.add(base + 5);
        else if (q.equals("5")) { /* power chord: no third */ }
        else n.add(base + (minor || q.equals("dim") ? 3 : 4));
        n.add(base + (q.equals("dim") ? 6 : 7));
        if (q.equals("6")) n.add(base + 9);
        if (q.equals("7")) n.add(base + 10);
        if (q.equals("M7")) n.add(base + 11);
        if (q.equals("9")) { n.add(base + 10); n.add(base + 14); } // dominant ninth (or minor ninth): the seventh and the ninth
        int bassPc = root;
        if (slash != null && !slash.isEmpty() && slash.charAt(0) >= 'A' && slash.charAt(0) <= 'G') {
            int[] l2 = new int[1];
            bassPc = rootOf(slash, l2);
        }
        int[] out = new int[n.size() + 1];
        out[0] = 36 + bassPc; // C2 and up
        for (int i = 0; i < n.size(); i++) out[i + 1] = n.get(i);
        return out;
    }

    /** The chords of one bar (space separated), each in an equal part of the bar, strummed at its start. */
    private static void addChords(float[] mix, int barStart, int barFrames, String spec, java.util.Map<String, float[]> cache, short[][] kit) {
        String[] tokens = spec.trim().isEmpty() ? new String[0] : spec.trim().split("\\s+");
        if (tokens.length == 0) return;
        int seg = barFrames / tokens.length;
        for (int k = 0; k < tokens.length; k++) {
            int[] notes = chordNotes(tokens[k]);
            if (notes == null) continue;
            int at = barStart + k * seg;
            int hold = (int) (seg * 0.92);
            addNote(mix, at, hold, notes[0], 7000f, 0.45f, cache, kit); // bass
            for (int i = 1; i < notes.length; i++) {
                addNote(mix, at + i * (RATE / 90), hold, notes[i], 3800f, 0.8f, cache, kit); // strummed upwards, about 11 ms apart
            }
            if (tokens.length == 1) { // a whole bar on one chord: a lighter strike on the third beat
                int again = at + barFrames / 2;
                for (int i = 1; i < notes.length; i++) {
                    addNote(mix, again + i * (RATE / 90), barFrames / 2 - RATE / 100, notes[i], 2200f, 0.5f, cache, kit);
                }
            }
        }
    }

    /** A plucked note: a few harmonics with an exponential decay (tau seconds) and a short fade at the end of the hold. Each distinct note is synthesised once. */
    private static void addNote(float[] mix, int at, int hold, int midi, float amp, float tau, java.util.Map<String, float[]> cache, short[][] kit) {
        String key = midi + ":" + hold + ":" + amp + ":" + tau;
        float[] wave = cache.get(key);
        if (wave == null && kit != null) { // the recorded note nearest to this one, pitch-shifted by resampling
            int best = 0;
            for (int i = 1; i < KIT_NOTES.length; i++) {
                if (Math.abs(KIT_NOTES[i] - midi) < Math.abs(KIT_NOTES[best] - midi)) best = i;
            }
            short[] smp = kit[best];
            double ratio = Math.pow(2, (midi - KIT_NOTES[best]) / 12.0);
            float gain = amp / 28000f;
            int fade = Math.min(hold / 4, RATE / 25);
            wave = new float[hold];
            for (int i = 0; i < hold; i++) {
                double pos = i * ratio;
                int j = (int) pos;
                if (j + 1 >= smp.length) break;
                double v = smp[j] + (smp[j + 1] - smp[j]) * (pos - j);
                double env = i > hold - fade ? (double) (hold - i) / fade : 1.0;
                wave[i] = (float) (v * env * gain);
            }
            cache.put(key, wave);
        }
        if (wave == null) {
            wave = new float[hold];
            double f = 440.0 * Math.pow(2, (midi - 69) / 12.0);
            int fade = Math.min(hold / 4, RATE / 25);
            double w = 2 * Math.PI * f / RATE;
            for (int i = 0; i < hold; i++) {
                double t = (double) i / RATE;
                double v = Math.sin(w * i) + 0.45 * Math.sin(2 * w * i) + 0.2 * Math.sin(3 * w * i);
                double env = Math.exp(-t / tau) * Math.min(1.0, i / (RATE * 0.004));
                if (i > hold - fade) env *= (double) (hold - i) / fade;
                wave[i] = (float) (v * env * amp);
            }
            cache.put(key, wave);
        }
        int total = mix.length;
        for (int i = 0; i < wave.length; i++) mix[(at + i) % total] += wave[i];
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
        samples[STICK] = tone(0.05, 1800, 1400, 0.012, 0.0, rnd);
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
