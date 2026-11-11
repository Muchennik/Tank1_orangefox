package com.muchennik.galleryredirect;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Forwards the camera's "open in gallery" request to Google Photos (chooser if it is missing). */
public class Redirect extends Activity {
    private static final String PHOTOS = "com.google.android.apps.photos";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        Uri uri = in != null ? in.getData() : null;
        if (uri != null) {
            Intent out = new Intent(Intent.ACTION_VIEW);
            out.setDataAndType(uri, in.getType() != null ? in.getType() : "image/*");
            out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            out.setPackage(PHOTOS);
            try {
                startActivity(out);
            } catch (Exception e) {
                out.setPackage(null);
                try {
                    startActivity(Intent.createChooser(out, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
        }
        finish();
    }
}
