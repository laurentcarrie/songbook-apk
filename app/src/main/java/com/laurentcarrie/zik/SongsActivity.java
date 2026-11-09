package com.laurentcarrie.zik;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.Toast;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public class SongsActivity extends Activity {
    /** Optional: only show the songs of this collection (tag). */
    static final String EXTRA_TAG = "tag";
    /** Optional: show the songs of this setlist (book), in the book order. */
    static final String EXTRA_BOOK = "book";

    /** Everything loaded for this screen, and the part of it matching the search (displayed). */
    private final List<Song> loaded = new ArrayList<>();
    private final List<Song> songs = new ArrayList<>();
    private String query = "";
    private String tag, book;
    private ListView list;
    private boolean authorFirst, fuzzyMode, recentOnly, starOnly;
    /** How many songs the "last opened" button lists. */
    static final int RECENT_COUNT = 10;

    /** Notes that the song with this pdf (path relative to the delivery) was just opened: it goes first. */
    static void addRecent(SharedPreferences prefs, String pdf) {
        List<String> r = recents(prefs);
        r.remove(pdf);
        r.add(0, pdf);
        while (r.size() > RECENT_COUNT) r.remove(r.size() - 1);
        prefs.edit().putString("recent", String.join("\n", r)).apply();
    }

    private static final int STAR_GOLD = Color.rgb(255, 193, 7), STAR_GREY = Color.rgb(170, 170, 170);

    /** The ids of the starred songs. */
    private java.util.Set<String> starred() {
        java.util.Set<String> r = new java.util.HashSet<>();
        for (String s : prefs.getString("starred", "").split("\n")) if (!s.isEmpty()) r.add(s);
        return r;
    }

    private void toggleStar(Song song) {
        java.util.Set<String> r = starred();
        if (!r.remove(song.id)) r.add(song.id);
        prefs.edit().putString("starred", String.join("\n", r)).apply();
        if (starOnly) applyFilter(); // an unstarred song leaves the starred list
        else ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    /** On: coloured with white bold text; off: the plain grey button. */
    private static void paintToggle(ToggleButton b, boolean on, int onColor, android.graphics.drawable.Drawable offBackground) {
        if (on) {
            b.setBackgroundColor(onColor);
            b.setTextColor(Color.WHITE);
            b.setTypeface(null, Typeface.BOLD);
        } else {
            b.setBackground(offBackground);
            b.setTextColor(Color.DKGRAY);
            b.setTypeface(null, Typeface.NORMAL);
        }
    }

    /** The pdfs of the songs opened last, the latest first. */
    static List<String> recents(SharedPreferences prefs) {
        List<String> r = new ArrayList<>();
        for (String s : prefs.getString("recent", "").split("\n")) if (!s.isEmpty()) r.add(s);
        return r;
    }
    private SharedPreferences prefs;
    // Same fonts and colors as the song pdf: blue Jana Skrivana title, orange frcursive author
    private Typeface titleFont, authorFont;
    private static final int TITLE_COLOR = Color.rgb(0, 0, 255);
    private static final int AUTHOR_COLOR = Color.rgb(255, 128, 0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tag = getIntent().getStringExtra(EXTRA_TAG);
        book = getIntent().getStringExtra(EXTRA_BOOK);
        setTitle(tag != null ? tag : book != null ? book : "Chansons");
        titleFont = Typeface.createFromAsset(getAssets(), "fonts/title.otf");
        authorFont = Typeface.createFromAsset(getAssets(), "fonts/author.otf");
        prefs = getSharedPreferences("songbook", MODE_PRIVATE);
        authorFirst = prefs.getBoolean("authorFirst", true);
        fuzzyMode = prefs.getBoolean("fuzzy", true);

        // Toggle: sort and display per author / title, or per title / author
        ToggleButton toggle = new ToggleButton(this);
        toggle.setTextOn("Auteur / Titre");
        toggle.setTextOff("Titre / Auteur");
        toggle.setChecked(authorFirst);
        toggle.setOnCheckedChangeListener((b, checked) -> {
            authorFirst = checked;
            prefs.edit().putBoolean("authorFirst", checked).apply();
            resort();
        });

        // Search (fuzzy or exact, per the checkbox) on "author title" or "title author", following the toggle
        EditText search = new EditText(this);
        search.setHint("Rechercher");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) {
                query = e.toString();
                applyFilter();
            }
        });

        // Checked: fuzzy (letters in order); unchecked: exact (the text as typed, ignoring case and accents)
        CheckBox fuzzyBox = new CheckBox(this);
        fuzzyBox.setText("Approximative");
        fuzzyBox.setChecked(fuzzyMode);
        fuzzyBox.setOnCheckedChangeListener((b, checked) -> {
            fuzzyMode = checked;
            prefs.edit().putBoolean("fuzzy", checked).apply();
            applyFilter();
        });
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchRow.addView(fuzzyBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setAdapter(new SongAdapter());
        list.setOnItemClickListener((p, v, pos, id) -> open(songs.get(pos)));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        // the ten songs opened last, latest first, instead of the whole list
        recentOnly = prefs.getBoolean("recentOnly", false);
        ToggleButton recent = new ToggleButton(this);
        recent.setTextOn("✓ 10 derniers");
        recent.setTextOff("10 derniers");
        recent.setAllCaps(false);
        final android.graphics.drawable.Drawable offLook = recent.getBackground();
        final android.graphics.drawable.Drawable offBackground = offLook == null ? null : offLook.getConstantState().newDrawable();
        Runnable paint = () -> paintToggle(recent, recentOnly, Color.rgb(46, 158, 79), offBackground);
        recent.setChecked(recentOnly);
        toggle.setEnabled(!recentOnly);
        paint.run();
        recent.setOnCheckedChangeListener((b, checked) -> {
            recentOnly = checked;
            prefs.edit().putBoolean("recentOnly", checked).apply();
            toggle.setEnabled(!checked);
            paint.run();
            resort();
        });
        // only the starred songs
        starOnly = prefs.getBoolean("starOnly", false);
        ToggleButton starButton = new ToggleButton(this);
        starButton.setTextOn("★");
        starButton.setTextOff("★");
        starButton.setAllCaps(false);
        final android.graphics.drawable.Drawable starOff = starButton.getBackground() == null ? null
                : starButton.getBackground().getConstantState().newDrawable();
        Runnable paintStar = () -> paintToggle(starButton, starOnly, STAR_GOLD, starOff);
        starButton.setChecked(starOnly);
        paintStar.run();
        starButton.setOnCheckedChangeListener((b, checked) -> {
            starOnly = checked;
            prefs.edit().putBoolean("starOnly", checked).apply();
            paintStar.run();
            applyFilter();
        });
        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        toggles.addView(toggle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toggles.addView(recent, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toggles.addView(starButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f));
        layout.addView(toggles, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(layout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Storage.ensureAccess(this)) return;
        try {
            loaded.clear();
            List<Song> all = World.songs(Storage.WORLD);
            if (book != null) {
                for (Book b : World.books(Storage.WORLD)) {
                    if (b.name.equals(book)) loaded.addAll(World.songsOfBook(all, b));
                }
            } else {
                loaded.addAll(tag == null ? all : World.songsWithTag(all, tag));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de lire " + Storage.WORLD + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        resort();
    }

    /** A setlist keeps the order of its book; everything else follows the toggle. */
    private void resort() {
        if (book == null) World.sort(loaded, authorFirst);
        applyFilter();
    }

    private void applyFilter() {
        songs.clear();
        List<Song> source = loaded;
        if (recentOnly) { // the songs opened last, the latest first
            source = new ArrayList<>();
            for (String pdf : recents(prefs)) {
                for (Song s : loaded) if (s.pdf.equals(pdf)) source.add(s);
            }
        }
        java.util.Set<String> stars = starOnly ? starred() : null;
        for (Song s : source) {
            if (stars != null && !stars.contains(s.id)) continue;
            String text = authorFirst ? s.author + " " + s.title : s.title + " " + s.author;
            if (fuzzyMode ? fuzzy(query, text) : exact(query, text)) songs.add(s);
        }
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    /** True when the characters of the query appear in order in the text (case and accent insensitive). */
    private static boolean fuzzy(String query, String text) {
        String q = normalize(query).replace(" ", ""), t = normalize(text);
        int i = 0;
        for (int j = 0; j < t.length() && i < q.length(); j++) {
            if (t.charAt(j) == q.charAt(i)) i++;
        }
        return i == q.length();
    }

    /** True when the text contains the query as typed (case and accent insensitive). */
    private static boolean exact(String query, String text) {
        return normalize(text).contains(normalize(query).trim());
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }

    private void open(Song song) {
        if (song.pdf.isEmpty()) {
            Toast.makeText(this, "Pas de PDF pour cette chanson", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, MainActivity.class);
        // Hand over the whole displayed list so the viewer can go to the
        // previous / next song of it.
        List<String> pdfs = new ArrayList<>(), titles = new ArrayList<>(), mp3s = new ArrayList<>(), warnings = new ArrayList<>();
        List<Integer> tempos = new ArrayList<>();
        int index = 0;
        for (Song s : songs) {
            if (s.pdf.isEmpty()) continue;
            if (s == song) index = pdfs.size();
            pdfs.add(new File(Storage.DELIVERY, s.pdf).getPath());
            mp3s.add(String.join("\n", s.mp3));
            warnings.add(String.join("\n", s.warnings));
            tempos.add(s.tempo);
            titles.add(authorFirst ? s.author + " - " + s.title : s.title + " - " + s.author);
        }
        i.putExtra(MainActivity.EXTRA_PDFS, pdfs.toArray(new String[0]));
        int[] t = new int[tempos.size()];
        for (int k = 0; k < t.length; k++) t[k] = tempos.get(k);
        i.putExtra(MainActivity.EXTRA_TEMPOS, t);
        i.putExtra(MainActivity.EXTRA_MP3S, mp3s.toArray(new String[0]));
        i.putExtra(MainActivity.EXTRA_WARNINGS, warnings.toArray(new String[0]));
        i.putExtra(MainActivity.EXTRA_TITLES, titles.toArray(new String[0]));
        i.putExtra(MainActivity.EXTRA_INDEX, index);
        startActivity(i);
    }

    private class SongAdapter extends BaseAdapter {
        @Override public int getCount() { return songs.size(); }
        @Override public Object getItem(int i) { return songs.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = new LinearLayout(SongsActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(40, 24, 40, 24);

            TextView author = new TextView(SongsActivity.this);
            author.setTextSize(22);
            author.setTypeface(authorFont);
            author.setTextColor(AUTHOR_COLOR);
            author.setText(songs.get(i).author);

            TextView title = new TextView(SongsActivity.this);
            title.setTextSize(32);
            title.setTypeface(titleFont);
            title.setTextColor(TITLE_COLOR);
            title.setText(songs.get(i).title);

            if (authorFirst) {
                row.addView(author);
                row.addView(title);
            } else {
                row.addView(title);
                row.addView(author);
            }
            // the star: gold when the song is starred, grey otherwise; a tap changes it
            final Song song = songs.get(i);
            TextView star = new TextView(SongsActivity.this);
            star.setText("★");
            star.setTextSize(36);
            star.setGravity(android.view.Gravity.CENTER);
            star.setPadding(24, 0, 24, 0);
            star.setTextColor(starred().contains(song.id) ? STAR_GOLD : STAR_GREY);
            star.setOnClickListener(v -> toggleStar(song));
            LinearLayout line = new LinearLayout(SongsActivity.this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            line.addView(row, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(star, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return line;
        }
    }
}
