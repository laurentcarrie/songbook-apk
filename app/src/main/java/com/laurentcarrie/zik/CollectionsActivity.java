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

/** One collection per tag; opening one lists the songs that carry the tag. */
public class CollectionsActivity extends Activity {
    private final List<String> tags = new ArrayList<>();
    private final List<Integer> counts = new ArrayList<>();
    private ListView list;
    private Typeface titleFont, authorFont;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Collections");
        titleFont = Typeface.createFromAsset(getAssets(), "fonts/title.otf");
        authorFont = Typeface.createFromAsset(getAssets(), "fonts/author.otf");
        list = new ListView(this);
        list.setAdapter(new TagAdapter());
        list.setOnItemClickListener((p, v, pos, id) -> {
            Intent i = new Intent(this, SongsActivity.class);
            i.putExtra(SongsActivity.EXTRA_TAG, tags.get(pos));
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
            tags.clear();
            counts.clear();
            for (String t : World.collections(songs)) {
                tags.add(t);
                counts.add(World.songsWithTag(songs, t).size());
            }
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de lire " + Storage.WORLD + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    private class TagAdapter extends BaseAdapter {
        @Override public int getCount() { return tags.size(); }
        @Override public Object getItem(int i) { return tags.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = new LinearLayout(CollectionsActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(40, 24, 40, 24);

            TextView name = new TextView(CollectionsActivity.this);
            name.setTextSize(32);
            name.setTypeface(titleFont);
            name.setTextColor(Color.rgb(0, 0, 255));
            name.setText(tags.get(i));

            TextView count = new TextView(CollectionsActivity.this);
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
