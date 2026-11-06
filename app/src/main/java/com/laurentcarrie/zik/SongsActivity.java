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
    private boolean authorFirst, fuzzyMode;
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
        layout.addView(toggle, new LinearLayout.LayoutParams(
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
        for (Song s : loaded) {
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
        List<String> pdfs = new ArrayList<>(), titles = new ArrayList<>(), mp3s = new ArrayList<>();
        List<Integer> tempos = new ArrayList<>();
        int index = 0;
        for (Song s : songs) {
            if (s.pdf.isEmpty()) continue;
            if (s == song) index = pdfs.size();
            pdfs.add(new File(Storage.DELIVERY, s.pdf).getPath());
            mp3s.add(String.join("\n", s.mp3));
            tempos.add(s.tempo);
            titles.add(authorFirst ? s.author + " - " + s.title : s.title + " - " + s.author);
        }
        i.putExtra(MainActivity.EXTRA_PDFS, pdfs.toArray(new String[0]));
        int[] t = new int[tempos.size()];
        for (int k = 0; k < t.length; k++) t[k] = tempos.get(k);
        i.putExtra(MainActivity.EXTRA_TEMPOS, t);
        i.putExtra(MainActivity.EXTRA_MP3S, mp3s.toArray(new String[0]));
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
            return row;
        }
    }
}
