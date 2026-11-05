package com.example.hello;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;

/** Location of the delivery on the tablet, and the storage permission it needs. */
final class Storage {
    static final File DELIVERY = new File("/sdcard/Download/songbook/delivery");
    static final File WORLD = new File(DELIVERY, "world.yml");

    private Storage() { }

    /** True when files can be read; otherwise opens the settings page to grant it. */
    static boolean ensureAccess(Activity a) {
        if (Environment.isExternalStorageManager()) return true;
        Toast.makeText(a, "Grant \"All files access\", then come back", Toast.LENGTH_LONG).show();
        a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + a.getPackageName())));
        return false;
    }
}
