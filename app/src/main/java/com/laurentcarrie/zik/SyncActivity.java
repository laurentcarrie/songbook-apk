package com.laurentcarrie.zik;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

public class SyncActivity extends Activity {
    private static final String PDF_LABEL = "PDF (world.yml, PDF, PDF bruts)", MP3_LABEL = "MP3",
            RENDERS_LABEL = "MP3 renders";
    private Button syncButton;
    private CheckBox pdfBox, mp3Box, rendersBox, forceBox;
    private SharedPreferences prefs;
    private TextView status, summary;
    /** Per archive (pdf, mp3, mp3-renders): 0 up to date, 1 update available, 2 unreachable, -1 not checked yet. */
    private final int[] state = {-1, -1, -1};
    private ProgressBar bar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(64, 64, 64, 64);

        setTitle("Synchronisation");
        prefs = getSharedPreferences("songbook", MODE_PRIVATE);
        summary = new TextView(this);
        summary.setTextSize(22);
        summary.setTextColor(Color.WHITE);
        summary.setPadding(32, 24, 32, 24);
        summary.setText("Vérification de l'état…");
        summary.setBackground(pill(Color.GRAY));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, 0, 0, 32);
        layout.addView(summary, slp);
        pdfBox = addCheckBox(layout, PDF_LABEL, "sync_pdf", true);
        mp3Box = addCheckBox(layout, MP3_LABEL, "sync_mp3", false);
        rendersBox = addCheckBox(layout, RENDERS_LABEL, "sync_renders", false);
        forceBox = addCheckBox(layout, "Forcer le téléchargement (même à jour)", "sync_force", false);
        syncButton = addButton(layout, "Sync", this::sync);
        status = new TextView(this);
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        layout.addView(status);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        layout.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkUpToDate();
    }

    /** Checks, in the background, which archives changed on the server, and says so next to each box. */
    private void checkUpToDate() {
        if (!Environment.isExternalStorageManager()) return;
        new Thread(() -> {
            String[] names = {"pdf", "mp3", "mp3-renders"};
            CheckBox[] boxes = {pdfBox, mp3Box, rendersBox};
            String[] labels = {PDF_LABEL, MP3_LABEL, RENDERS_LABEL};
            for (int i = 0; i < names.length; i++) {
                String suffix;
                try {
                    Storage.ArchiveInfo info = Storage.archiveInfo(names[i]);
                    boolean tarballOk = Storage.isUpToDate(names[i], prefs, info);
                    // The pdf group also holds world.yml, which is not in the tarball
                    boolean worldOk = !names[i].equals("pdf") || Storage.worldUpToDate(prefs);
                    boolean ok = tarballOk && worldOk;
                    state[i] = ok ? 0 : 1;
                    suffix = ok ? " — à jour"
                            : tarballOk ? " — mise à jour disponible (world.yml)" : " — mise à jour disponible";
                } catch (Exception e) {
                    state[i] = 2;
                    suffix = " — état inconnu (hors ligne ?)";
                }
                final int k = i;
                final String text = labels[i] + suffix;
                runOnUiThread(() -> boxes[k].setText(text));
            }
            runOnUiThread(this::updateSummary);
        }).start();
    }

    private static android.graphics.drawable.GradientDrawable pill(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(24);
        return g;
    }

    /**
     * Explains the color of the Sync button of the main menu: red when move-the-line.org cannot be
     * reached, orange when a ticked archive has a newer version, green when all ticked ones are up to date.
     */
    private void updateSummary() {
        String[] labels = {"PDF", "MP3", "MP3 renders"};
        boolean[] ticked = {pdfBox.isChecked(), mp3Box.isChecked(), rendersBox.isChecked()};
        boolean unchecked = false, offline = false;
        java.util.List<String> outdated = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if (state[i] == -1) unchecked = true;
            if (state[i] == 2) offline = true;
            if (ticked[i] && state[i] == 1) outdated.add(labels[i]);
        }
        String text;
        int color;
        if (unchecked) {
            text = "Vérification de l'état…";
            color = Color.GRAY;
        } else if (offline) {
            text = "Pas de connexion. move-the-line.org est injoignable, la synchro est impossible.";
            color = Color.rgb(198, 40, 40);
        } else if (!outdated.isEmpty()) {
            text = "Connexion disponible, mise à jour disponible pour : " + String.join(", ", outdated)
                    + ". Appuyez sur Sync pour la télécharger.";
            color = Color.rgb(239, 108, 0);
        } else {
            text = "Connexion disponible, les archives cochées sont à jour.";
            color = Color.rgb(46, 125, 50);
        }
        summary.setText(text + (unchecked ? "" : "\n(Seules les archives cochées comptent.)"));
        summary.setBackground(pill(color));
    }

    private static String describe(int n, String what) {
        return n == Storage.UNCHANGED ? what + " déjà à jour" : n + " " + what;
    }

    private CheckBox addCheckBox(LinearLayout layout, String label, String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(22);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            updateSummary();
        });
        layout.addView(c, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private void setSyncEnabled(boolean on) {
        syncButton.setEnabled(on);
        pdfBox.setEnabled(on);
        mp3Box.setEnabled(on);
        rendersBox.setEnabled(on);
        forceBox.setEnabled(on);
    }

    private void sync() {
        final boolean force = forceBox.isChecked(), doPdf = pdfBox.isChecked(), doMp3 = mp3Box.isChecked(), doRenders = rendersBox.isChecked();
        if (!doPdf && !doMp3 && !doRenders) {
            Toast.makeText(this, "Cochez au moins une case", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Storage.ensureAccess(this)) return;
        status.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        setSyncEnabled(false);
        Storage.Progress prog = (t, pc) -> runOnUiThread(() -> {
            status.setText(t);
            bar.setIndeterminate(pc < 0);
            if (pc >= 0) bar.setProgress(pc);
        });
        new Thread(() -> {
            String msg;
            try {
                java.util.List<String> done = new java.util.ArrayList<>();
                if (doPdf) {
                    Storage.downloadWorld(prefs, prog);
                    done.add(describe(Storage.syncArchive("pdf", prefs, force, prog), "fichiers PDF"));
                }
                if (doMp3) done.add(describe(Storage.syncArchive("mp3", prefs, force, prog), "MP3"));
                if (doRenders) done.add(describe(Storage.syncArchive("mp3-renders", prefs, force, prog), "MP3 renders"));
                msg = "Synchro : " + String.join(", ", done);
            } catch (Exception e) {
                msg = "Échec de la synchro : " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                bar.setVisibility(View.GONE);
                setSyncEnabled(true);
                status.setText(m);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                checkUpToDate();
            });
        }).start();
    }

    private Button addButton(LinearLayout layout, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(28);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200);
        lp.setMargins(0, 24, 0, 24);
        layout.addView(b, lp);
        return b;
    }
}
