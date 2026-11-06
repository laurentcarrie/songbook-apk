package com.laurentcarrie.zik;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import java.io.File;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String EXTRA_PDF = "pdf";
    static final String EXTRA_TITLE = "title";
    /** Setlist mode: the pdfs and titles of every song, and the one to open first. */
    static final String EXTRA_PDFS = "pdfs";
    static final String EXTRA_TITLES = "titles";
    static final String EXTRA_INDEX = "index";
    /** Per song, its mp3 files as "name=path" lines (path relative to the delivery). */
    static final String EXTRA_MP3S = "mp3s";
    /** Per song, its tempo in BPM (0 when unknown). */
    static final String EXTRA_TEMPOS = "tempos";

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int page = 0;
    private String[] setlistPdfs, setlistTitles, setlistMp3s;
    private FlowLayout mp3Row;
    private android.media.MediaPlayer player;
    private Button playing, pauseButton, stopButton, drumButton;
    private DrumMachine drums;
    private int[] setlistTempos;
    /** Tempo of the current song (0 if unknown), and the tempo the loop is set to. */
    private int songTempo, drumBpm;
    private LinearLayout drumPanel;
    private Button resetButton;
    private TextView drumBpmLabel;
    private BeatIndicator beatView;
    private boolean paused;
    // Range of the file to play: sliders for the position, the start and the end
    private LinearLayout rangePanel;
    private SeekBar posBar, startBar, endBar;
    private TextView posLabel, startLabel, endLabel;
    private android.widget.CheckBox loopBox;
    private int startMs, endMs, durationMs, minGapMs = 2000;
    private File currentFile;
    private SharedPreferences prefs;
    private final android.os.Handler handler = new android.os.Handler();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, 200);
        }
    };
    private int songIndex = 0;

    private ScrollView scrollView;
    private ImageView imageView;
    private TextView pageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        drums = DrumMachine.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        pageView = new TextView(this);
        pageView.setTextSize(20);
        pageView.setPadding(32, 16, 32, 16);

        bar.addView(pageView);
        // The mp3 buttons go in a row of their own, under the bar, and wrap when there are many
        mp3Row = new FlowLayout(this);
        mp3Row.setPadding(32, 0, 32, 0);
        drumButton = new Button(this);
        drumButton.setAllCaps(false);
        drumButton.setVisibility(View.GONE);
        drumButton.setOnClickListener(v -> toggleDrums());
        bar.addView(drumButton);
        pauseButton = new Button(this);
        pauseButton.setText("⏸");
        pauseButton.setTextSize(22);
        pauseButton.setVisibility(View.GONE);
        pauseButton.setOnClickListener(v -> togglePause());
        bar.addView(pauseButton);
        stopButton = new Button(this);
        stopButton.setText("■");
        stopButton.setTextSize(22);
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> stopMp3());
        bar.addView(stopButton);

        imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.DKGRAY);
        scrollView.setFocusable(false);
        scrollView.addView(imageView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        // Swipe up / down turns the page, once the page cannot scroll any further
        // in that direction (so a page taller than the screen is still scrollable).
        scrollView.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private boolean atTop, atBottom;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        atTop = !scrollView.canScrollVertically(-1);
                        atBottom = !scrollView.canScrollVertically(1);
                        break;
                    case MotionEvent.ACTION_UP:
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        float min = 120 * getResources().getDisplayMetrics().density / 2.6f;
                        if (Math.abs(dx) > 1.5f * min && Math.abs(dx) > 2 * Math.abs(dy)) {
                            // swipe left: next song, swipe right: previous song
                            turnSong(dx < 0 ? 1 : -1);
                        } else if (dy < -min && atBottom && !scrollView.canScrollVertically(1)) turn(1);
                        else if (dy > min && atTop && !scrollView.canScrollVertically(-1)) turn(-1);
                        break;
                    default:
                        break;
                }
                return false; // let the ScrollView scroll as usual
            }
        });
        scrollView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol)) showPage();
        });

        prefs = getSharedPreferences("songbook", MODE_PRIVATE);
        drumPanel = new LinearLayout(this);
        drumPanel.setOrientation(LinearLayout.HORIZONTAL);
        drumPanel.setGravity(Gravity.CENTER_VERTICAL);
        drumPanel.setPadding(32, 0, 32, 8);
        drumPanel.setVisibility(View.GONE);
        drumBpmLabel = new TextView(this);
        drumBpmLabel.setTextSize(20);
        drumBpmLabel.setMinWidth(260);
        drumPanel.addView(drumBpmLabel);
        for (int[] step : new int[][]{{-10}, {-1}, {1}, {10}}) {
            drumPanel.addView(drumStep(step[0]));
        }
        Button reset = new Button(this);
        reset.setText("↺ " );
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> setDrumBpm(songTempo > 0 ? songTempo : drumBpm));
        drumPanel.addView(reset);
        resetButton = reset;

        rangePanel = new LinearLayout(this);
        rangePanel.setOrientation(LinearLayout.VERTICAL);
        rangePanel.setPadding(32, 0, 32, 8);
        rangePanel.setVisibility(View.GONE);
        posLabel = addSlider("Position", false);
        posBar = lastBar;
        startLabel = addSlider("Début", false);
        startBar = lastBar;
        endLabel = addSlider("Fin", false);
        endBar = lastBar;
        posBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override void changed(int v) {
                if (player != null) player.seekTo(v);
                posLabel.setText("Position  " + time(v));
            }
        });
        startBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override void changed(int v) {
                startMs = Math.min(v, Math.max(0, endMs - minGapMs));
                if (startMs != v) startBar.setProgress(startMs);
                startLabel.setText("Début  " + time(startMs));
                saveRange();
                if (player != null && player.getCurrentPosition() < startMs) player.seekTo(startMs);
            }
        });
        endBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override void changed(int v) {
                endMs = Math.max(v, Math.min(durationMs, startMs + minGapMs));
                if (endMs != v) endBar.setProgress(endMs);
                endLabel.setText("Fin  " + time(endMs));
                saveRange();
            }
        });

        loopBox = new android.widget.CheckBox(this);
        loopBox.setText("Boucle (repart du début à la fin)");
        loopBox.setTextSize(18);
        loopBox.setChecked(prefs.getBoolean("loop", false));
        loopBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("loop", on).apply());
        rangePanel.addView(loopBox);

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        beatView = new BeatIndicator(this, 90);
        beatView.setVisibility(View.GONE);
        root.addView(beatView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(mp3Row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(drumPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(rangePanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        setlistPdfs = getIntent().getStringArrayExtra(EXTRA_PDFS);
        setlistTitles = getIntent().getStringArrayExtra(EXTRA_TITLES);
        setlistMp3s = getIntent().getStringArrayExtra(EXTRA_MP3S);
        setlistTempos = getIntent().getIntArrayExtra(EXTRA_TEMPOS);
        if (setlistPdfs != null) {
            songIndex = getIntent().getIntExtra(EXTRA_INDEX, 0);
            if (Storage.ensureAccess(this)) openSong(songIndex);
        } else {
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (title != null) setTitle(title);
            String path = getIntent().getStringExtra(EXTRA_PDF);
            if (path != null && Storage.ensureAccess(this)) openPdf(new File(path));
        }
    }

    /** Setlist mode: shows song i of the setlist, from its first page. */
    private void openSong(int i) {
        songIndex = i;
        setTitle(setlistTitles[i]);
        showMp3s(setlistMp3s == null ? "" : setlistMp3s[i]);
        showDrums(setlistTempos == null ? 0 : setlistTempos[i]);
        openPdf(new File(setlistPdfs[i]));
    }

    /** Moves to the next (+1) or previous (-1) song of the setlist. */
    private void turnSong(int delta) {
        if (setlistPdfs == null) return;
        int n = songIndex + delta;
        if (n < 0 || n >= setlistPdfs.length) return;
        openSong(n);
    }

    /**
     * The drum button of the song: a rock loop at the song tempo, hidden when the tempo is unknown.
     * A loop already playing (from the Batterie page or the previous song) goes on, at this song's tempo.
     */
    private void showDrums(int tempo) {
        songTempo = tempo;
        if (drums.isPlaying()) {
            drumBpm = tempo > 0 ? tempo : drums.getBpm();
            if (drumBpm != drums.getBpm()) drums.start(drumBpm, drums.getStyle());
        } else {
            drumBpm = tempo;
        }
        updateDrumUi();
    }

    private Button drumStep(int delta) {
        Button b = new Button(this);
        b.setText(delta > 0 ? "+" + delta : "−" + (-delta));
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(v -> setDrumBpm(drumBpm + delta));
        return b;
    }

    private void setDrumBpm(int bpm) {
        drumBpm = Math.max(40, Math.min(220, bpm));
        if (drums.isPlaying()) drums.start(drumBpm, drums.getStyle());
        updateDrumUi();
    }

    private void toggleDrums() {
        if (drums.isPlaying()) {
            drums.stop();
        } else {
            drums.start(drumBpm, 0); // style 0: rock
        }
        updateDrumUi();
    }

    private void updateDrumUi() {
        boolean on = drums.isPlaying();
        drumButton.setVisibility(songTempo > 0 || on ? View.VISIBLE : View.GONE);
        drumButton.setText((on ? "■ 🥁 " : "🥁 ") + (on ? drumBpm : songTempo));
        drumPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        beatView.setVisibility(on ? View.VISIBLE : View.GONE);
        drumBpmLabel.setText("Tempo " + drumBpm + " BPM");
        resetButton.setText(songTempo > 0 ? "↺ " + songTempo : "↺");
    }

    /** One button per mp3 of the song that is on the tablet; a tap plays it, another tap pauses / resumes it. */
    private void showMp3s(String spec) {
        stopMp3();
        mp3Row.removeAllViews();
        for (String line : spec.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String name = line.substring(0, eq);
            File f = new File(Storage.DELIVERY, line.substring(eq + 1));
            if (!f.isFile()) continue;
            Button b = new Button(this);
            b.setText("♪ " + name);
            b.setTag(name);
            b.setAllCaps(false);
            b.setOnClickListener(v -> toggleMp3(b, name, f));
            mp3Row.addView(b);
        }
    }

    /** Tap on a file: plays it. Tap on the file being played: pauses / resumes it. */
    private void toggleMp3(Button b, String name, File f) {
        if (playing == b && player != null) {
            togglePause();
            return;
        }
        stopMp3();
        try {
            player = new android.media.MediaPlayer();
            player.setDataSource(f.getPath());
            player.setOnCompletionListener(mp -> {
                if (loopBox.isChecked() && currentFile != null) {
                    mp.seekTo(startMs); // the range ends with the file: start over
                    mp.start();
                } else {
                    // End of the file: back to the start of the range, paused, sliders still usable
                    mp.seekTo(startMs);
                    paused = true;
                    posBar.setProgress(startMs);
                    updateControls();
                }
            });
            player.prepare();
            currentFile = f;
            setupRange();
            player.seekTo(startMs);
            player.start();
            playing = b;
            paused = false;
            updateControls();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        } catch (Exception e) {
            stopMp3();
            Toast.makeText(this, "Impossible de lire le MP3 : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void togglePause() {
        if (player == null) return;
        if (paused) {
            player.start();
            paused = false;
        } else {
            player.pause();
            paused = true;
        }
        updateControls();
    }

    private SeekBar lastBar;

    /** Adds a labelled slider to the range panel; the slider is left in lastBar. */
    private TextView addSlider(String label, boolean unused) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setTextSize(18);
        t.setText(label);
        t.setMinWidth(300);
        SeekBar b = new SeekBar(this);
        row.addView(t);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rangePanel.addView(row);
        lastBar = b;
        return t;
    }

    /** SeekBar listener that only reacts to the user moving the slider. */
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        abstract void changed(int value);
        @Override public void onProgressChanged(SeekBar b, int v, boolean fromUser) { if (fromUser) changed(v); }
        @Override public void onStartTrackingTouch(SeekBar b) { }
        @Override public void onStopTrackingTouch(SeekBar b) { }
    }

    private static String time(int ms) {
        int s = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void saveRange() {
        if (currentFile != null) {
            prefs.edit().putString("range_" + currentFile.getPath(), startMs + "," + endMs).apply();
        }
    }

    /** Sliders for a freshly loaded file: the range saved for it, or the whole file. */
    private void setupRange() {
        durationMs = player.getDuration();
        minGapMs = Math.min(2000, durationMs / 2); // the two ends can never get closer than this
        startMs = 0;
        endMs = durationMs;
        String saved = prefs.getString("range_" + currentFile.getPath(), null);
        if (saved != null) {
            try {
                String[] a = saved.split(",");
                int s0 = Integer.parseInt(a[0]), e0 = Integer.parseInt(a[1]);
                if (s0 >= 0 && e0 <= durationMs && e0 - s0 >= minGapMs) { startMs = s0; endMs = e0; }
            } catch (Exception ignored) { }
        }
        posBar.setMax(durationMs);
        startBar.setMax(durationMs);
        endBar.setMax(durationMs);
        startBar.setProgress(startMs);
        endBar.setProgress(endMs);
        posBar.setProgress(startMs);
        startLabel.setText("Début  " + time(startMs));
        endLabel.setText("Fin  " + time(endMs));
        posLabel.setText("Position  " + time(startMs));
    }

    /** Follows the playback: moves the position slider, and pauses at the end of the range. */
    private void tick() {
        if (player == null || paused) return;
        int pos = player.getCurrentPosition();
        if (pos >= endMs && loopBox.isChecked()) {
            player.seekTo(startMs);
            pos = startMs;
        } else if (pos >= endMs) {
            try { player.pause(); } catch (IllegalStateException ignored) { }
            player.seekTo(startMs);
            paused = true;
            pos = startMs;
            updateControls();
        }
        posBar.setProgress(pos);
        posLabel.setText("Position  " + time(pos));
    }

    /** ▶ marks the file being played, ⏸ the one that is paused; the ⏸ / ▶ and ■ buttons appear while a file is loaded. */
    private void updateControls() {
        for (int i = 0; i < mp3Row.getChildCount(); i++) {
            Button b = (Button) mp3Row.getChildAt(i);
            String name = (String) b.getTag();
            b.setText(b != playing ? "♪ " + name : (paused ? "⏸ " : "▶ ") + name);
        }
        boolean loaded = player != null;
        pauseButton.setVisibility(loaded ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(loaded ? View.VISIBLE : View.GONE);
        rangePanel.setVisibility(loaded ? View.VISIBLE : View.GONE);
        pauseButton.setText(paused ? "▶" : "⏸");
    }

    private void stopMp3() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        playing = null;
        paused = false;
        currentFile = null;
        handler.removeCallbacks(ticker);
        updateControls();
    }

    private void openPdf(File file) {
        try {
            closePdf();
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            page = 0;
            showPage();
        } catch (Exception e) {
            closePdf();
            Toast.makeText(this, "Impossible d'ouvrir le PDF : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void closePdf() {
        try {
            if (renderer != null) renderer.close();
            if (pfd != null) pfd.close();
        } catch (Exception ignored) { }
        renderer = null;
        pfd = null;
    }

    private void showPage() {
        if (renderer == null) return;
        int vw = scrollView.getWidth();
        if (vw == 0) return; // layout listener will call us back

        PdfRenderer.Page p = renderer.openPage(page);
        float scale = (float) vw / p.getWidth(); // fit width
        Bitmap bmp = Bitmap.createBitmap(vw, Math.max(1, (int) (p.getHeight() * scale)),
                Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        p.close();

        imageView.setImageBitmap(bmp);
        scrollView.scrollTo(0, 0);
        pageView.setText("Page " + (page + 1) + " / " + renderer.getPageCount());
    }

    private void turn(int delta) {
        if (renderer == null) return;
        int n = page + delta;
        if (n < 0 || n >= renderer.getPageCount()) return;
        page = n;
        showPage();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) turn(1);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DEL:
                if (event.getRepeatCount() == 0) turn(-1);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onDestroy() {
        stopMp3(); // the drum loop goes on: it is stopped with its own button
        closePdf();
        super.onDestroy();
    }
}
