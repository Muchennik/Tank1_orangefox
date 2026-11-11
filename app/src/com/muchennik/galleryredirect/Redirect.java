package com.muchennik.galleryredirect;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** Forwards the camera's "open in gallery" request to Google Photos (chooser if it is missing). */
public class Redirect extends Activity {
    private static final String PHOTOS = "com.google.android.apps.photos";
    private static final String TAG = "GalleryRedirect";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        Uri uri = in != null ? in.getData() : null;
        Log.i(TAG, "in: " + in);
        if (uri != null) {
            Intent out = new Intent(Intent.ACTION_VIEW);
            out.setDataAndType(uri, in.getType() != null ? in.getType() : "image/*");
            // no FLAG_GRANT_READ_URI_PERMISSION: the camera does not grant this stub access to the
            // MediaStore uri, and re-granting a uri we cannot read throws SecurityException.
            // Google Photos reads MediaStore with its own media permission.
            out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.setPackage(PHOTOS);
            try {
                startActivity(out);
            } catch (Exception e) {
                Log.w(TAG, "Google Photos: " + e);
                out.setPackage(null);
                try {
                    startActivity(Intent.createChooser(out, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e2) {
                    Log.e(TAG, "chooser: " + e2);
                }
            }
        }
        finish();
    }
}
