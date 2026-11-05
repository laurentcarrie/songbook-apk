package com.example.hello;

import java.util.List;

/** One book of world.yml: a setlist, i.e. an ordered list of song ids. */
final class Book {
    final String name;
    final List<String> songIds;

    Book(String name, List<String> songIds) {
        this.name = name;
        this.songIds = songIds;
    }
}
