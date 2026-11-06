package com.laurentcarrie.zik;

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

import java.util.ArrayList;
import java.util.List;

/** One setlist per book; opening one lists the songs of the book, in order. */
public class SetlistsActivity extends Activity {
    private final List<String> names = new ArrayList<>();
    private final List<Integer> counts = new ArrayList<>();
    private ListView list;
    private Typeface titleFont, authorFont;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Setlists");
        titleFont = Typeface.createFromAsset(getAssets(), "fonts/title.otf");
        authorFont = Typeface.createFromAsset(getAssets(), "fonts/author.otf");
        list = new ListView(this);
        list.setAdapter(new TagAdapter());
        list.setOnItemClickListener((p, v, pos, id) -> {
            Intent i = new Intent(this, SongsActivity.class);
            i.putExtra(SongsActivity.EXTRA_BOOK, names.get(pos));
            startActivity(i);
        });
        setContentView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Storage.ensureAccess(this)) return;
        try {
            List<Song> songs = World.songs(Storage.WORLD);
            names.clear();
            counts.clear();
            for (Book b : World.books(Storage.WORLD)) {
                names.add(b.name);
                counts.add(World.songsOfBook(songs, b).size());
            }
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de lire " + Storage.WORLD + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    private class TagAdapter extends BaseAdapter {
        @Override public int getCount() { return names.size(); }
        @Override public Object getItem(int i) { return names.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = new LinearLayout(SetlistsActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(40, 24, 40, 24);

            TextView name = new TextView(SetlistsActivity.this);
            name.setTextSize(32);
            name.setTypeface(titleFont);
            name.setTextColor(Color.rgb(0, 0, 255));
            name.setText(names.get(i));

            TextView count = new TextView(SetlistsActivity.this);
            count.setTextSize(22);
            count.setTypeface(authorFont);
            count.setTextColor(Color.rgb(255, 128, 0));
            int n = counts.get(i);
            count.setText(n + (n == 1 ? " chanson" : " chansons"));

            row.addView(name);
            row.addView(count);
            return row;
        }
    }
}
