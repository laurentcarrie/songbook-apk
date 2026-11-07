package com.laurentcarrie.zik;

import java.util.List;

/** One song of world.yml. */
final class Song {
    final String id, author, title, pdf;
    final List<String> tags;
    /** Beats per minute, 0 when world.yml does not give it. */
    int tempo;
    /** Delivered mp3 files as "name=path" (path relative to the delivery root). */
    final List<String> mp3 = new java.util.ArrayList<>();
    /** Problems found in the sources of the song by the build, shown on the song page. */
    final List<String> warnings = new java.util.ArrayList<>();

    Song(String id, String author, String title, String pdf, List<String> tags) {
        this.id = id;
        this.author = author;
        this.title = title;
        this.pdf = pdf;
        this.tags = tags;
    }
}
