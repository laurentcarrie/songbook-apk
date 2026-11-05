package com.example.hello;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SongsActivity extends Activity {
    /** Optional: only show the songs of this collection (tag). */
    static final String EXTRA_TAG = "tag";
    /** Optional: show the songs of this setlist (book), in the book order. */
    static final String EXTRA_BOOK = "book";

    private final List<Song> songs = new ArrayList<>();
    private String tag, book;
    private ListView list;
    // Same fonts and colors as the song pdf: blue Jana Skrivana title, orange frcursive author
    private Typeface titleFont, authorFont;
    private static final int TITLE_COLOR = Color.rgb(0, 0, 255);
    private static final int AUTHOR_COLOR = Color.rgb(255, 128, 0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tag = getIntent().getStringExtra(EXTRA_TAG);
        book = getIntent().getStringExtra(EXTRA_BOOK);
        setTitle(tag != null ? tag : book != null ? book : "Songs");
        titleFont = Typeface.createFromAsset(getAssets(), "fonts/title.otf");
        authorFont = Typeface.createFromAsset(getAssets(), "fonts/author.otf");
        list = new ListView(this);
        list.setAdapter(new SongAdapter());
        list.setOnItemClickListener((p, v, pos, id) -> open(songs.get(pos)));
        setContentView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Storage.ensureAccess(this)) return;
        try {
            songs.clear();
            List<Song> all = World.songs(Storage.WORLD);
            if (book != null) {
                for (Book b : World.books(Storage.WORLD)) {
                    if (b.name.equals(book)) songs.addAll(World.songsOfBook(all, b));
                }
            } else {
                songs.addAll(tag == null ? all : World.songsWithTag(all, tag));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Cannot read " + Storage.WORLD + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    private void open(Song song) {
        if (song.pdf.isEmpty()) {
            Toast.makeText(this, "No pdf for this song", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, MainActivity.class);
        // Hand over the whole displayed list so the viewer can go to the
        // previous / next song of it.
        List<String> pdfs = new ArrayList<>(), titles = new ArrayList<>();
        int index = 0;
        for (Song s : songs) {
            if (s.pdf.isEmpty()) continue;
            if (s == song) index = pdfs.size();
            pdfs.add(new File(Storage.DELIVERY, s.pdf).getPath());
            titles.add(s.author + " - " + s.title);
        }
        i.putExtra(MainActivity.EXTRA_PDFS, pdfs.toArray(new String[0]));
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

            row.addView(author);
            row.addView(title);
            return row;
        }
    }
}
