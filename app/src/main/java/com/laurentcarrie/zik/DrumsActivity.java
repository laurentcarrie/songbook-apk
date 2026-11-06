package com.laurentcarrie.zik;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;


/** A drum machine: plays a drum loop, synthesised on the tablet, at the tempo you choose. */
public class DrumsActivity extends Activity {
    private static final int MIN_BPM = 40, MAX_BPM = 220;
    private static final String[] STYLES = DrumMachine.STYLES;

    private SharedPreferences prefs;
    private int bpm, style;
    private TextView bpmView;
    private SeekBar bpmBar;
    private Button playButton;
    private DrumMachine drums;
    private android.view.View styleGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Batterie");
        prefs = getSharedPreferences("songbook", MODE_PRIVATE);
        drums = DrumMachine.get(this);
        bpm = Math.max(MIN_BPM, Math.min(MAX_BPM, prefs.getInt("drum_bpm", 100)));
        style = Math.max(0, Math.min(STYLES.length - 1, prefs.getInt("drum_style", 0)));
        if (drums.isPlaying()) { // a loop is already going (started here or from a song): show it
            bpm = drums.getBpm();
            style = drums.getStyle();
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(64, 64, 64, 64);

        bpmView = new TextView(this);
        bpmView.setTextSize(52);
        bpmView.setGravity(Gravity.CENTER);

        LinearLayout bpmRow = new LinearLayout(this);
        bpmRow.setGravity(Gravity.CENTER);
        bpmRow.addView(stepButton("−10", -10));
        bpmRow.addView(stepButton("−1", -1));
        bpmRow.addView(bpmView, new LinearLayout.LayoutParams(400, LinearLayout.LayoutParams.WRAP_CONTENT));
        bpmRow.addView(stepButton("+1", 1));
        bpmRow.addView(stepButton("+10", 10));
        layout.addView(bpmRow);

        bpmBar = new SeekBar(this);
        bpmBar.setMax(MAX_BPM - MIN_BPM);
        bpmBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean fromUser) { if (fromUser) setBpm(MIN_BPM + v); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, 32, 0, 32);
        layout.addView(bpmBar, blp);

        RadioGroup group = new RadioGroup(this);
        styleGroup = group;
        group.setOrientation(RadioGroup.HORIZONTAL);
        group.setGravity(Gravity.CENTER);
        for (int i = 0; i < STYLES.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(STYLES[i]);
            r.setTextSize(24);
            r.setId(100 + i);
            r.setPadding(16, 16, 48, 16);
            group.addView(r);
        }
        group.check(100 + style);
        group.setOnCheckedChangeListener((g, id) -> {
            style = id - 100;
            prefs.edit().putInt("drum_style", style).apply();
            restartIfPlaying();
        });
        layout.addView(group);

        BeatIndicator beats = new BeatIndicator(this, 180);
        LinearLayout.LayoutParams belp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        belp.setMargins(0, 48, 0, 0);
        layout.addView(beats, belp);

        playButton = new Button(this);
        playButton.setTextSize(30);
        playButton.setAllCaps(false);
        playButton.setTextColor(Color.WHITE);
        playButton.setBackgroundColor(Color.rgb(194, 24, 91));
        playButton.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 220);
        plp.setMargins(0, 64, 0, 0);
        layout.addView(playButton, plp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
        refresh();
    }

    private Button stepButton(String label, int delta) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(20);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(v -> setBpm(bpm + delta));
        return b;
    }

    private void setBpm(int value) {
        bpm = Math.max(MIN_BPM, Math.min(MAX_BPM, value));
        prefs.edit().putInt("drum_bpm", bpm).apply();
        refresh();
        restartIfPlaying();
    }

    private void refresh() {
        bpmView.setText(bpm + " BPM");
        bpmBar.setProgress(bpm - MIN_BPM);
        playButton.setText(drums.isPlaying() ? "■  Stop" : "▶  Jouer");
    }

    private void toggle() {
        if (drums.isPlaying()) {
            drums.stop();
        } else {
            drums.start(bpm, style);
        }
        refresh();
    }

    private void restartIfPlaying() {
        if (drums.isPlaying()) drums.start(bpm, style);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (drums.isPlaying() && (bpm != drums.getBpm() || style != drums.getStyle())) {
            bpm = drums.getBpm(); // changed from a song page
            style = drums.getStyle();
            ((RadioGroup) styleGroup).check(100 + style);
        }
        refresh();
    }
}
