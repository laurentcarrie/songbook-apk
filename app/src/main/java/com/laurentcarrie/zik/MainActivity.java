package com.laurentcarrie.zik;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    /** Per song, the warnings of the build about it, one per line (world.yml). */
    static final String EXTRA_WARNINGS = "warnings";

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int page = 0;
    private String[] setlistPdfs, setlistTitles, setlistMp3s;
    private FlowLayout mp3Row;
    private android.media.MediaPlayer player;
    private Button playing, pauseButton, stopButton, drumButton, viewButton;
    private boolean lyricsMode; // the page shows the syllable of the active bar instead of the bar
    private DrumMachine drums;
    private int[] setlistTempos;
    private String[] setlistWarnings;
    private TextView warningView, drumCaption;
    /** Tempo of the current song (0 if unknown), and the tempo the loop is set to. */
    private int songTempo, drumBpm;
    private LinearLayout drumPanel, drumBarPanel;
    private TextView drumBarLabel;
    private android.widget.EditText drumBarEdit;
    private boolean drumPaused, settingBarText; // paused to type a bar number; true while the field is set by the code
    private int drumFirstBar = 1; // bar the drum track starts on
    private final Runnable drumTicker = new Runnable() {
        @Override public void run() {
            drumTick();
            handler.postDelayed(this, 100);
        }
    };
    private Button resetButton;
    private TextView drumBpmLabel;
    private BeatIndicator beatView;
    private boolean paused;
    // Range of the file to play: sliders for the position, the start and the end
    private LinearLayout rangePanel;
    private SeekBar posBar, startBar, endBar;
    private TextView posLabel, startLabel, endLabel;
    private TextView loopBox;
    private boolean loopOn;
    private int startMs, endMs, durationMs, minGapMs = 2000;
    private File currentFile;
    private SharedPreferences prefs;
    // Bar slider: highlights bar n of the chord chart, when the song has bar positions
    private TextView sectionLabel, missingClicks;
    private android.widget.FrameLayout sectionRow;
    private BarCells cells;
    private int highlight; // bar number shown, 0 for none
    /** Times (seconds) of the beats of the recording, bar 1 beat 1 first; null when not delivered. */
    private double[] clickTimes;
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
        // the chart shows either the active bar or the active syllable on the lyrics page
        viewButton = new Button(this);
        viewButton.setAllCaps(false);
        viewButton.setVisibility(View.GONE);
        viewButton.setOnClickListener(v -> {
            lyricsMode = !lyricsMode;
            prefs.edit().putBoolean("lyrics_view", lyricsMode).apply();
            updateViewButton();
            if (highlight >= 1) selectBar(highlight); else showPage();
        });
        bar.addView(viewButton);
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
        lyricsMode = prefs.getBoolean("lyrics_view", false);
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

        // first bar of the drum track, when the song has a structure
        drumBarPanel = new LinearLayout(this);
        drumBarPanel.setOrientation(LinearLayout.HORIZONTAL);
        drumBarPanel.setGravity(Gravity.CENTER_VERTICAL);
        drumBarPanel.setPadding(32, 0, 32, 8);
        drumBarPanel.setVisibility(View.GONE);
        drumBarLabel = new TextView(this);
        drumBarLabel.setTextSize(20);
        drumBarLabel.setText("Mesure  #");
        drumBarPanel.addView(drumBarLabel);
        drumBarEdit = new android.widget.EditText(this);
        drumBarEdit.setTextSize(20);
        drumBarEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        drumBarEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        drumBarEdit.setSelectAllOnFocus(true);
        drumBarEdit.setMinWidth(160);
        drumBarEdit.setFocusableInTouchMode(false); // while playing a tap only pauses
        drumBarEdit.setOnClickListener(v -> pauseDrumsForEdit());
        drumBarEdit.setOnEditorActionListener((v, action, ev) -> {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        drumBarEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                if (settingBarText || !drumPaused || cells == null) return;
                try {
                    int n = Integer.parseInt(e.toString().trim());
                    if (n < 1 || n > cells.count) return;
                    drumFirstBar = n;
                    selectBar(n); // the circle moves to the bar typed
                } catch (NumberFormatException ignored) { }
            }
        });
        drumBarPanel.addView(drumBarEdit);
        drumCaption = new TextView(this);
        drumCaption.setTextSize(20);
        drumCaption.setPadding(32, 0, 0, 0);
        drumBarPanel.addView(drumCaption);

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
                posLabel.setText("Position  " + barTime(v));
                followBar(v);
            }
        });
        startBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override void changed(int v) {
                startMs = Math.min(v, Math.max(0, endMs - minGapMs));
                if (startMs != v) startBar.setProgress(startMs);
                startLabel.setText("Début  " + barTime(startMs));
                saveRange();
                if (player != null && player.getCurrentPosition() < startMs) player.seekTo(startMs);
            }
        });
        endBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override void changed(int v) {
                endMs = Math.max(v, Math.min(durationMs, startMs + minGapMs));
                if (endMs != v) endBar.setProgress(endMs);
                endLabel.setText("Fin  " + barTime(endMs));
                saveRange();
            }
        });

        loopBox = new TextView(this) {
            @Override protected void onDraw(android.graphics.Canvas c) {
                // the loop icon is drawn here, centred on the view: a circular arrow, plus an oblique bar when off
                android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                p.setColor(getCurrentTextColor());
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                float s = getTextSize();
                float cx = getWidth() / 2f, cy = getHeight() / 2f, r = s * 0.36f;
                p.setStrokeWidth(s / 9f);
                // arc from -50 to 230 degrees (clockwise), open at the top
                c.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), -50, 280, false, p);
                // arrow head at the end of the arc, pointing along the clockwise tangent
                double a = Math.toRadians(230);
                float ex = cx + r * (float) Math.cos(a), ey = cy + r * (float) Math.sin(a);
                float tx = -(float) Math.sin(a), ty = (float) Math.cos(a); // tangent
                float nx = -ty, ny = tx;                                     // normal
                float h = s * 0.22f, w = s * 0.16f;
                android.graphics.Path head = new android.graphics.Path();
                head.moveTo(ex + tx * h, ey + ty * h);
                head.lineTo(ex + nx * w, ey + ny * w);
                head.lineTo(ex - nx * w, ey - ny * w);
                head.close();
                p.setStyle(android.graphics.Paint.Style.FILL);
                c.drawPath(head, p);
                if (!loopOn) { // off: oblique bar through the centre of the circle
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(s / 18f);
                    float k = r * 1.25f;
                    c.drawLine(cx - k, cy + k, cx + k, cy - k, p);
                }
            }
        };
        loopBox.setText(" "); // the text only gives the view its size
        loopBox.setTextSize(36);
        loopBox.setMinWidth((int) (72 * getResources().getDisplayMetrics().scaledDensity));
        loopBox.setGravity(android.view.Gravity.CENTER);
        loopBox.setPadding(24, 8, 24, 8);
        loopOn = prefs.getBoolean("loop", false);
        paintLoop();
        loopBox.setOnClickListener(v -> {
            loopOn = !loopOn;
            prefs.edit().putBoolean("loop", loopOn).apply();
            paintLoop();
        });
        loopBox.setVisibility(View.GONE);
        bar.addView(loopBox, bar.indexOfChild(stopButton) + 1);

        // the name of the section being played rides above the thumb of the position slider
        sectionRow = new android.widget.FrameLayout(this);
        sectionLabel = new TextView(this);
        sectionLabel.setTextSize(18);
        sectionLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        sectionLabel.setSingleLine(true);
        sectionLabel.setIncludeFontPadding(false);
        sectionLabel.setPadding(0, 0, 0, 0);
        sectionRow.addView(sectionLabel, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        sectionRow.setVisibility(View.GONE);
        rangePanel.addView(sectionRow, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // thin red notice when the bars are only estimated from the tempo
        missingClicks = new TextView(this);
        missingClicks.setTextSize(12);
        missingClicks.setTextColor(Color.RED);
        missingClicks.setText("Clics manquants : mesure estimée d'après le tempo");
        missingClicks.setVisibility(View.GONE);
        rangePanel.addView(missingClicks, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rangePanel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeSectionLabel());

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // what the build found wrong in the sources of the song (world.yml warnings)
        warningView = new TextView(this);
        warningView.setTextSize(13);
        warningView.setTextColor(Color.rgb(200, 0, 0));
        warningView.setPadding(32, 0, 32, 4);
        warningView.setVisibility(View.GONE);
        root.addView(warningView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        beatView = new BeatIndicator(this, 90);
        beatView.setVisibility(View.GONE);
        root.addView(beatView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(mp3Row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(drumPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(drumBarPanel, new LinearLayout.LayoutParams(
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
        setlistWarnings = getIntent().getStringArrayExtra(EXTRA_WARNINGS);
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
        String root = Storage.DELIVERY.getPath() + "/";
        if (setlistPdfs[i].startsWith(root)) { // the song goes first in the "5 derniers" list
            SongsActivity.addRecent(prefs, setlistPdfs[i].substring(root.length()));
        }
        String warnings = setlistWarnings == null || i >= setlistWarnings.length ? "" : setlistWarnings[i];
        warningView.setText(warnings.isEmpty() ? "" : "⚠ " + warnings.replace("\n", "\n⚠ "));
        warningView.setVisibility(warnings.isEmpty() ? View.GONE : View.VISIBLE);
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
            if (drumBpm != drums.getBpm()) startDrums(drums.getStyle(), currentDrumBar());
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
        if (drums.isPlaying()) startDrums(drums.getStyle(), currentDrumBar());
        updateDrumUi();
    }

    /** Starts the drums at drumBpm; with the bars of the song, the whole song plays, with its chords, from bar index startBar. */
    private void startDrums(int style, int startBar) {
        boolean song = cells != null && cells.count > 1;
        drums.start(drumBpm, style, song ? cells.chords : null, song ? startBar : 0);
    }

    /** Index (0-based) of the bar the drums are on, for a restart at another tempo. */
    private int currentDrumBar() {
        return highlight >= 1 ? highlight - 1 : drumFirstBar - 1;
    }

    private void toggleDrums() {
        if (drums.isPlaying()) {
            drums.stop();
            drumPaused = false;
            drumFirstBar = 1; // the next start is from the top
        } else {
            startDrums(0, drumFirstBar - 1); // style 0: rock
        }
        updateDrumUi();
    }

    /** Drum track: the chart follows its bars, counted from the first bar chosen with the slider. */
    /** Tap on the bar number: the drums pause and the number can be typed; the drum button resumes from it. */
    private void pauseDrumsForEdit() {
        if (!drums.isPlaying()) return;
        drumFirstBar = highlight >= 1 ? highlight : drumFirstBar;
        drums.stop();
        drumPaused = true;
        updateDrumUi();
        drumBarEdit.setFocusableInTouchMode(true);
        drumBarEdit.requestFocus();
        drumBarEdit.selectAll();
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(drumBarEdit, 0);
    }

    private void setBarText(int n) {
        settingBarText = true;
        drumBarEdit.setText(String.valueOf(n));
        settingBarText = false;
    }

    private void drumTick() {
        if (!drums.isPlaying() || cells == null || drums.getLoopBars() != cells.count) return;
        int[] pos = drums.position(); // {bar, beat} of the song
        if (pos == null) return;
        int bar = pos[0] + 1;
        if (bar != highlight) { selectBar(bar); setBarText(bar); }
    }

    private void updateDrumUi() {
        boolean on = drums.isPlaying();
        if (on) drumPaused = false;
        boolean shown = on || drumPaused; // paused to edit the bar: the panels stay
        drumButton.setVisibility(songTempo > 0 || shown ? View.VISIBLE : View.GONE);
        drumButton.setText((on ? "■ 🥁 " : "🥁 ") + (on ? drumBpm : songTempo));
        drumPanel.setVisibility(shown ? View.VISIBLE : View.GONE);
        beatView.setVisibility(on ? View.VISIBLE : View.GONE);
        mp3Row.setVisibility(shown || player != null ? View.GONE : View.VISIBLE); // no list while something plays
        boolean bars = shown && cells != null && cells.count > 1;
        if (!shown) drumBarEdit.setFocusableInTouchMode(false);
        if (on) drumBarEdit.setFocusableInTouchMode(false);
        drumBarPanel.setVisibility(bars ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(drumTicker);
        if (on) {
            handler.post(drumTicker);
        } else {
            if (!drumPaused && player == null && highlight != 0) { // nothing plays: no bar marked
                highlight = 0; drumCaption.setText("");
                if (renderer != null) showPage();
            }
        }
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
                if (loopOn && currentFile != null) {
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
        startLabel.setText("Début  " + barTime(startMs));
        endLabel.setText("Fin  " + barTime(endMs));
        posLabel.setText("Position  " + barTime(startMs));
    }

    /** Follows the playback: moves the position slider, and pauses at the end of the range. */
    private void tick() {
        if (player == null || paused) return;
        int pos = player.getCurrentPosition();
        if (pos >= endMs && loopOn) {
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
        posLabel.setText("Position  " + barTime(pos));
        followBar(pos);
    }

    /** The recording is the one the clicks were measured on: move the highlight to the bar at this time. */
    private void followBar(int pos) {
        if (!following()) { placeSectionLabel(); return; }
        int bar = barAt(pos);
        if (bar != highlight) selectBar(bar);
        placeSectionLabel();
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
        loopBox.setVisibility(loaded ? View.VISIBLE : View.GONE);
        rangePanel.setVisibility(loaded ? View.VISIBLE : View.GONE);
        mp3Row.setVisibility(loaded || drums.isPlaying() ? View.GONE : View.VISIBLE); // the list comes back on stop
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
        if (highlight != 0) { // nothing playing: no bar marked
            highlight = 0; drumCaption.setText("");
            if (renderer != null) showPage();
        }
    }

    private void openPdf(File file) {
        try {
            closePdf();
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            page = 0;
            loadCells(file);
            showPage();
        } catch (Exception e) {
            closePdf();
            Toast.makeText(this, "Impossible d'ouvrir le PDF : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Loads the bar positions and beat times of the song; the chart shows bar 1's page, with no bar marked until a recording plays. */
    private void loadCells(File pdf) {
        cells = BarCells.load(pdf);
        clickTimes = loadClicks(pdf);
        highlight = 0; drumCaption.setText("");
        if (cells != null) {
            drumFirstBar = Math.max(1, Math.min(drumFirstBar, cells.count));
            setBarText(drumFirstBar);
        }
        updateViewButton();
        updateDrumUi();
        // a drum loop going on from the previous song: play this one's chords from its first bar
        if (drums.isPlaying() && cells != null && cells.count > 1) startDrums(drums.getStyle(), 0);
        if (cells == null) return;
        if (cells.page[0] > 0) page = Math.min(cells.page[0] - 1, renderer.getPageCount() - 1);
    }

    /** The section of bar n (1-based), then the syllable sung on its first beat, in red. */
    private CharSequence caption(int n) {
        String section = cells.section[n - 1], lyric = cells.lyric[n - 1];
        if (lyric.isEmpty()) return section;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(section);
        int from = b.length();
        b.append("  ▸ ").append(lyric);
        b.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(200, 0, 0)), from, b.length(), 0);
        b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), from, b.length(), 0);
        return b;
    }

    /** Highlights bar n (1-based), turning to its page if needed. */
    private void selectBar(int n) {
        if (cells == null || n < 1 || n > cells.count) return;
        highlight = n;
        drumCaption.setText(caption(n));
        int p = cells.page[n - 1];
        if (lyricsView()) { // the lyrics page, on the syllable of this bar
            int lb = lyricBarOf(n);
            p = lb >= 1 ? cells.sylPage[lb - 1] : 0;
        }
        if (p > 0 && renderer != null) page = Math.min(p - 1, renderer.getPageCount() - 1);
        showPage();
    }

    /** The page shows the lyrics: chosen with the toggle, and the song has its syllables placed. */
    private boolean lyricsView() {
        return lyricsMode && cells != null && cells.hasSyllables;
    }

    /** The last bar up to n, in the section of bar n, that has a syllable on the lyrics page; 0 for none. */
    private int lyricBarOf(int n) {
        for (int k = n; k >= 1 && cells.section[k - 1].equals(cells.section[n - 1]); k--) {
            if (cells.sylPage[k - 1] > 0) return k;
        }
        return 0;
    }

    private void updateViewButton() {
        viewButton.setVisibility(cells != null && cells.hasSyllables ? View.VISIBLE : View.GONE);
        viewButton.setText(lyricsMode ? "Paroles" : "Grille");
    }

    /** True while the chart follows the recording: bar positions and beat times are there. */
    private boolean following() {
        return cells != null && (clickTimes != null || songTempo > 0) && currentFile != null
                && "mp3".equals(currentFile.getParentFile().getName());
    }

    /** Puts the section name over the thumb of the position slider, left-aligned on it at the start and right-aligned at the end. */
    private void placeSectionLabel() {
        boolean on = following() && highlight >= 1;
        sectionRow.setVisibility(on ? View.VISIBLE : View.GONE);
        missingClicks.setVisibility(on && clickTimes == null ? View.VISIBLE : View.GONE);
        if (!on || posBar.getWidth() == 0) return;
        sectionLabel.setText(caption(highlight));
        sectionLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float usable = posBar.getWidth() - posBar.getPaddingLeft() - posBar.getPaddingRight();
        float frac = posBar.getMax() == 0 ? 0 : (float) posBar.getProgress() / posBar.getMax();
        float thumb = posBar.getLeft() + posBar.getPaddingLeft() + frac * usable;
        float x = thumb - frac * sectionLabel.getMeasuredWidth();
        float max = sectionRow.getWidth() - sectionLabel.getMeasuredWidth();
        sectionLabel.setTranslationX(Math.max(0, Math.min(x, max)));
    }

    /** The beat times of the recording: delivery/clicks/&lt;pdf stem&gt;-clicks.yml, a list of seconds. */
    private static double[] loadClicks(File pdf) {
        String name = pdf.getName();
        if (name.endsWith(".pdf")) name = name.substring(0, name.length() - 4);
        File f = new File(Storage.DELIVERY, "clicks/" + name + "-clicks.yml");
        if (!f.isFile()) return null;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            Object root = new org.yaml.snakeyaml.Yaml().load(in);
            Object list = root instanceof java.util.Map ? ((java.util.Map<?, ?>) root).get("clicks") : null;
            if (!(list instanceof java.util.List) || ((java.util.List<?>) list).isEmpty()) return null;
            java.util.List<?> l = (java.util.List<?>) list;
            double[] t = new double[l.size()];
            for (int i = 0; i < t.length; i++) t[i] = ((Number) l.get(i)).doubleValue();
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** The loop icon is green when the loop is on, grey when it is off. */
    private void paintLoop() {
        loopBox.setTextColor(loopOn ? 0xFF2E9E4F : 0xFF9E9E9E);
        loopBox.invalidate();
    }

    /** "#i  m:ss" (bar number then time) when the bars can be followed, else just the time. */
    private String barTime(int ms) {
        return following() ? "#" + barAt(ms) + "  " + time(ms) : time(ms);
    }

    /** Bar (1-based) being played at this time of the recording: 4 beats a bar, from the first click (or from the tempo when there are no clicks). */
    private int barAt(int ms) {
        double t = ms / 1000.0;
        if (clickTimes == null) {
            // no beat times: 4 beats a bar at the song tempo from the start of the file; when that
            // would run past the end of the file, squeeze the bars to fit the duration
            double barLen = 240.0 / songTempo;
            double d = durationMs / 1000.0;
            if (d > 0 && barLen * cells.count > d) barLen = d / cells.count;
            return Math.max(1, Math.min(cells.count, (int) (t / barLen) + 1));
        }
        int lo = 0, hi = clickTimes.length; // number of clicks at or before t
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (clickTimes[mid] <= t) lo = mid + 1; else hi = mid;
        }
        int bar = lo <= 0 ? 1 : (lo - 1) / 4 + 1;
        return Math.min(bar, cells.count);
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

        // the active bar, circled, when it is on this page
        int scrollTo = 0;
        boolean lyrics = lyricsView();
        if (lyrics && highlight >= 1) { // the syllable of the active bar, on the lyrics page
            int lb = lyricBarOf(highlight);
            if (lb >= 1 && cells.sylPage[lb - 1] == page + 1) {
                float k = bmp.getWidth() / cells.pageW;
                float pad = Math.max(4f, bmp.getWidth() / 150f);
                android.graphics.RectF r = new android.graphics.RectF(cells.sylX[lb - 1] * k - pad, cells.sylY[lb - 1] * k - pad,
                        (cells.sylX[lb - 1] + cells.sylW[lb - 1]) * k + pad, (cells.sylY[lb - 1] + cells.sylH[lb - 1]) * k + pad);
                Canvas canvas = new Canvas(bmp);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(3f, bmp.getWidth() / 200f));
                paint.setColor(Color.rgb(255, 80, 0));
                if (cells.lyric[lb - 1].isEmpty()) { // an empty mark (no lyrics on this bar): circled
                    float rad = Math.max(r.width(), r.height()) * 0.8f;
                    canvas.drawCircle(r.centerX(), r.centerY(), rad, paint);
                } else {
                    canvas.drawRoundRect(r, pad, pad, paint);
                }
                scrollTo = (int) r.top;
            }
        }
        if (!lyrics && cells != null && highlight >= 1 && cells.page[highlight - 1] == page + 1) {
            float k = bmp.getWidth() / cells.pageW;
            float cx = cells.x[highlight - 1] * k, cy = cells.y[highlight - 1] * k;
            float cw = cells.w[highlight - 1] * k, ch = cells.h[highlight - 1] * k;
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint();
            // circle the bar: an ellipse a little larger than the cell, so its corners stay inside
            float mx = cx + cw / 2, my = cy + ch / 2, rx = cw / 2 * 1.3f, ry = ch / 2 * 1.3f;
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(3f, bmp.getWidth() / 200f));
            paint.setColor(Color.rgb(255, 80, 0));
            canvas.drawOval(new android.graphics.RectF(mx - rx, my - ry, mx + rx, my + ry), paint);
            scrollTo = (int) cy;
        }
        // the dot of the pass being played, in a repeat: filled over the black one
        if (!lyrics && cells != null && highlight >= 1 && cells.dotPage[highlight - 1] == page + 1) {
            float k = bmp.getWidth() / cells.pageW;
            float r = cells.dotR[highlight - 1] * k;
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(255, 80, 0));
            canvas.drawCircle(cells.dotX[highlight - 1] * k, cells.dotY[highlight - 1] * k, r * 1.5f, paint);
        }

        imageView.setImageBitmap(bmp);
        final int target = scrollTo;
        // keep the bar in view: a page taller than the screen scrolls to it
        scrollView.post(() -> scrollView.scrollTo(0, Math.max(0, target - scrollView.getHeight() / 3)));
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
