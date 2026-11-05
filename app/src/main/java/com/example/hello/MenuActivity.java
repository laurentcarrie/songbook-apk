package com.example.hello;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

public class MenuActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(64, 64, 64, 64);

        addButton(layout, "Songs", () -> startActivity(new Intent(this, SongsActivity.class)));
        addButton(layout, "Collections", () -> startActivity(new Intent(this, CollectionsActivity.class)));
        addButton(layout, "Setlists", () -> startActivity(new Intent(this, SetlistsActivity.class)));

        setContentView(layout);
    }

    private void addButton(LinearLayout layout, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(28);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200);
        lp.setMargins(0, 24, 0, 24);
        layout.addView(b, lp);
    }
}
