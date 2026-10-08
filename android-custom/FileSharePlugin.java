package com.promodeals.mybookshelf;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

@CapacitorPlugin(name = "FileShare")
public class FileSharePlugin extends Plugin {
    @PluginMethod
    public void getPendingFiles(PluginCall call) {
        Intent intent = getActivity().getIntent();
        if (intent == null || (!Intent.ACTION_SEND.equals(intent.getAction())
                && !Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) {
            JSObject result = new JSObject();
            result.put("files", new JSArray());
            call.resolve(result);
            return;
        }
        try {
            JSArray files = readIntentFiles(intent);
            getActivity().setIntent(new Intent());
            JSObject result = new JSObject();
            result.put("files", files);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Could not import the shared file: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void pickFiles(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(call, intent, "pickFilesResult");
    }

    @ActivityCallback
    private void pickFilesResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.resolve(new JSObject().put("files", new JSArray()));
            return;
        }
        try {
            JSArray files = readIntentFiles(result.getData());
            JSObject response = new JSObject();
            response.put("files", files);
            call.resolve(response);
        } catch (Exception e) {
            call.reject("Could not read the selected file: " + e.getMessage(), e);
        }
    }

    private JSArray readIntentFiles(Intent intent) throws Exception {
        Set<Uri> uris = new LinkedHashSet<>();
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> extras = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (extras != null) uris.addAll(extras);
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null && Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
            if (uri != null) uris.add(uri);
        }
        if (uris.isEmpty() && intent.getData() != null) uris.add(intent.getData());

        JSArray files = new JSArray();
        for (Uri uri : uris) {
            JSObject file = readUri(uri);
            if (file != null) files.put(file);
        }
        return files;
    }

    private JSObject readUri(Uri uri) throws Exception {
        String name = "shared-file";
        String mime = getContext().getContentResolver().getType(uri);
        if (mime == null) mime = "application/octet-stream";
        Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (cursor.moveToFirst() && column >= 0) name = cursor.getString(column);
            } finally {
                cursor.close();
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        JSObject file = new JSObject();
        file.put("name", name);
        file.put("type", mime);
        file.put("base64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
        return file;
    }
}