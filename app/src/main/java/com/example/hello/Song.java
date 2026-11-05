package com.example.hello;

import java.util.List;

/** One song of world.yml. */
final class Song {
    final String id, author, title, pdf;
    final List<String> tags;

    Song(String id, String author, String title, String pdf, List<String> tags) {
        this.id = id;
        this.author = author;
        this.title = title;
        this.pdf = pdf;
        this.tags = tags;
    }
}
