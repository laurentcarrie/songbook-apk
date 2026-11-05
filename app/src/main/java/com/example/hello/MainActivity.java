package com.example.hello;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import java.io.File;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String EXTRA_PDF = "pdf";
    static final String EXTRA_TITLE = "title";
    /** Setlist mode: the pdfs and titles of every song, and the one to open first. */
    static final String EXTRA_PDFS = "pdfs";
    static final String EXTRA_TITLES = "titles";
    static final String EXTRA_INDEX = "index";

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int page = 0;
    private String[] setlistPdfs, setlistTitles;
    private int songIndex = 0;

    private ScrollView scrollView;
    private ImageView imageView;
    private TextView pageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        pageView = new TextView(this);
        pageView.setTextSize(20);
        pageView.setPadding(32, 16, 32, 16);

        bar.addView(pageView);

        imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.DKGRAY);
        scrollView.setFocusable(false);
        scrollView.addView(imageView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        // Swipe up / down turns the page, once the page cannot scroll any further
        // in that direction (so a page taller than the screen is still scrollable).
        scrollView.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private boolean atTop, atBottom;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        atTop = !scrollView.canScrollVertically(-1);
                        atBottom = !scrollView.canScrollVertically(1);
                        break;
                    case MotionEvent.ACTION_UP:
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        float min = 120 * getResources().getDisplayMetrics().density / 2.6f;
                        if (Math.abs(dx) > 1.5f * min && Math.abs(dx) > 2 * Math.abs(dy)) {
                            // swipe left: next song, swipe right: previous song
                            turnSong(dx < 0 ? 1 : -1);
                        } else if (dy < -min && atBottom && !scrollView.canScrollVertically(1)) turn(1);
                        else if (dy > min && atTop && !scrollView.canScrollVertically(-1)) turn(-1);
                        break;
                    default:
                        break;
                }
                return false; // let the ScrollView scroll as usual
            }
        });
        scrollView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol)) showPage();
        });

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        setlistPdfs = getIntent().getStringArrayExtra(EXTRA_PDFS);
        setlistTitles = getIntent().getStringArrayExtra(EXTRA_TITLES);
        if (setlistPdfs != null) {
            songIndex = getIntent().getIntExtra(EXTRA_INDEX, 0);
            if (Storage.ensureAccess(this)) openSong(songIndex);
        } else {
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (title != null) setTitle(title);
            String path = getIntent().getStringExtra(EXTRA_PDF);
            if (path != null && Storage.ensureAccess(this)) openPdf(new File(path));
        }
    }

    /** Setlist mode: shows song i of the setlist, from its first page. */
    private void openSong(int i) {
        songIndex = i;
        setTitle(setlistTitles[i]);
        openPdf(new File(setlistPdfs[i]));
    }

    /** Moves to the next (+1) or previous (-1) song of the setlist. */
    private void turnSong(int delta) {
        if (setlistPdfs == null) return;
        int n = songIndex + delta;
        if (n < 0 || n >= setlistPdfs.length) return;
        openSong(n);
    }

    private void openPdf(File file) {
        try {
            closePdf();
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            page = 0;
            showPage();
        } catch (Exception e) {
            closePdf();
            Toast.makeText(this, "Cannot open PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void closePdf() {
        try {
            if (renderer != null) renderer.close();
            if (pfd != null) pfd.close();
        } catch (Exception ignored) { }
        renderer = null;
        pfd = null;
    }

    private void showPage() {
        if (renderer == null) return;
        int vw = scrollView.getWidth();
        if (vw == 0) return; // layout listener will call us back

        PdfRenderer.Page p = renderer.openPage(page);
        float scale = (float) vw / p.getWidth(); // fit width
        Bitmap bmp = Bitmap.createBitmap(vw, Math.max(1, (int) (p.getHeight() * scale)),
                Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        p.close();

        imageView.setImageBitmap(bmp);
        scrollView.scrollTo(0, 0);
        pageView.setText("Page " + (page + 1) + " / " + renderer.getPageCount());
    }

    private void turn(int delta) {
        if (renderer == null) return;
        int n = page + delta;
        if (n < 0 || n >= renderer.getPageCount()) return;
        page = n;
        showPage();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) turn(1);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DEL:
                if (event.getRepeatCount() == 0) turn(-1);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onDestroy() {
        closePdf();
        super.onDestroy();
    }
}
