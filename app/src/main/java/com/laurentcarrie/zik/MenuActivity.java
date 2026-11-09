package com.laurentcarrie.zik;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MenuActivity extends Activity {
    private static final int GREEN = Color.rgb(46, 125, 50), ORANGE = Color.rgb(239, 108, 0),
            RED = Color.rgb(198, 40, 40);
    private static final int SONGS_COLOR = Color.rgb(21, 101, 192), COLLECTIONS_COLOR = Color.rgb(106, 27, 154),
            SETLISTS_COLOR = Color.rgb(0, 131, 143), DRUMS_COLOR = Color.rgb(194, 24, 91);
    private Button syncButton;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("songbook", MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(64, 64, 64, 64);

        addButton(layout, withOrangeIcon("♫", "  Chansons"), SONGS_COLOR,
                () -> startActivity(new Intent(this, SongsActivity.class)));
        addButton(layout, "🏷️  Collections", COLLECTIONS_COLOR,
                () -> startActivity(new Intent(this, CollectionsActivity.class)));
        addButton(layout, "📋  Setlists", SETLISTS_COLOR,
                () -> startActivity(new Intent(this, SetlistsActivity.class)));
        addButton(layout, "🥁  Batterie", DRUMS_COLOR,
                () -> startActivity(new Intent(this, DrumsActivity.class)));
        syncButton = addButton(layout, "🔄  Sync", Color.GRAY,
                () -> startActivity(new Intent(this, SyncActivity.class)));

        // the title bar: the app name on the left, the version of the apk (versionName in app/build.gradle) on the right
        try {
            String version = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            android.app.ActionBar bar = getActionBar();
            if (bar != null) {
                TextView name = new TextView(this);
                name.setText("laurent-zik");
                name.setTextSize(20);
                name.setTextColor(Color.rgb(33, 33, 33));
                TextView ver = new TextView(this);
                ver.setText(version);
                ver.setTextSize(16);
                ver.setTextColor(Color.GRAY);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(name, new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(ver, new LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setPadding(0, 0, 0, 0);
                bar.setDisplayShowTitleEnabled(false);
                bar.setDisplayShowCustomEnabled(true);
                bar.setCustomView(row, new android.app.ActionBar.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } catch (Exception ignored) { }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkSyncState();
    }

    /**
     * Colors the Sync button: red when the server cannot be reached, orange when a tarball
     * ticked on the sync page is not up to date, green otherwise.
     */
    private void checkSyncState() {
        if (!Environment.isExternalStorageManager()) return;
        new Thread(() -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            if (prefs.getBoolean("sync_pdf", true)) names.add("pdf");
            if (prefs.getBoolean("sync_mp3", false)) names.add("mp3");
            if (prefs.getBoolean("sync_renders", false)) names.add("mp3-renders");
            int color = GREEN;
            try {
                if (names.isEmpty()) Storage.archiveInfo("pdf"); // only tests the connection
                for (String n : names) {
                    if (!Storage.isUpToDate(n, prefs, Storage.archiveInfo(n))) color = ORANGE;
                }
            } catch (Exception e) {
                color = RED;
            }
            final int c = color;
            runOnUiThread(() -> {
                style(syncButton, c);
            });
        }).start();
    }

    /** Rounded colored background, darker while pressed, with white text. */
    private void style(Button b, int color) {
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        normal.setColor(color);
        normal.setCornerRadius(32);
        android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
        pressed.setColor(Color.rgb((int) (Color.red(color) * 0.7), (int) (Color.green(color) * 0.7),
                (int) (Color.blue(color) * 0.7)));
        pressed.setCornerRadius(32);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        b.setBackground(states);
        b.setTextColor(Color.WHITE);
    }

    /** A text glyph (it follows the text color, unlike an emoji) in orange, followed by the label. */
    private static CharSequence withOrangeIcon(String icon, String label) {
        android.text.SpannableString t = new android.text.SpannableString(icon + label);
        t.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(255, 152, 0)), 0, icon.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, icon.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setSpan(new android.text.style.RelativeSizeSpan(1.3f), 0, icon.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return t;
    }

    private Button addButton(LinearLayout layout, CharSequence label, int color, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(28);
        b.setAllCaps(false);
        style(b, color);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200);
        lp.setMargins(0, 24, 0, 24);
        layout.addView(b, lp);
        return b;
    }
}
