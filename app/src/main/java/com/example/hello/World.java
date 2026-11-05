package com.example.hello;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads the songs of world.yml. */
final class World {
    private World() { }

    /** Songs sorted by author, then title (case and accent insensitive). */
    static List<Song> songs(File worldYml) throws Exception {
        List<Song> songs = new ArrayList<>();
        try (InputStream in = new FileInputStream(worldYml)) {
            Object root = new Yaml().load(in);
            Object list = ((Map<?, ?>) root).get("songs");
            if (list instanceof List) {
                for (Object o : (List<?>) list) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    List<String> tags = new ArrayList<>();
                    if (m.get("tags") instanceof List) {
                        for (Object t : (List<?>) m.get("tags")) tags.add(str(t));
                    }
                    songs.add(new Song(str(m.get("id")), str(m.get("author")),
                            str(m.get("title")), str(m.get("pdf")), tags));
                }
            }
        }
        Collator collator = Collator.getInstance(Locale.FRENCH);
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(songs, (a, b) -> {
            int c = collator.compare(a.author, b.author);
            return c != 0 ? c : collator.compare(a.title, b.title);
        });
        return songs;
    }

    /** Books (setlists) sorted by name. */
    static List<Book> books(File worldYml) throws Exception {
        List<Book> books = new ArrayList<>();
        try (InputStream in = new FileInputStream(worldYml)) {
            Object list = ((Map<?, ?>) new Yaml().load(in)).get("books");
            if (list instanceof List) {
                for (Object o : (List<?>) list) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    List<String> ids = new ArrayList<>();
                    if (m.get("songs") instanceof List) {
                        for (Object id : (List<?>) m.get("songs")) ids.add(str(id));
                    }
                    books.add(new Book(str(m.get("name")), ids));
                }
            }
        }
        Collections.sort(books, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return books;
    }

    /** The songs of a setlist, in the order of the book; unknown ids are skipped. */
    static List<Song> songsOfBook(List<Song> songs, Book book) {
        java.util.Map<String, Song> byId = new java.util.HashMap<>();
        for (Song s : songs) byId.put(s.id, s);
        List<Song> result = new ArrayList<>();
        for (String id : book.songIds) {
            Song s = byId.get(id);
            if (s != null) result.add(s);
        }
        return result;
    }

    /** The songs of one collection: those whose tags contain the given tag. */
    static List<Song> songsWithTag(List<Song> songs, String tag) {
        List<Song> result = new ArrayList<>();
        for (Song s : songs) if (s.tags.contains(tag)) result.add(s);
        return result;
    }

    /** One collection per tag, sorted by name. */
    static List<String> collections(List<Song> songs) {
        java.util.TreeSet<String> tags = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Song s : songs) tags.addAll(s.tags);
        return new ArrayList<>(tags);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
