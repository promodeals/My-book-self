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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@CapacitorPlugin(name = "FileShare")
public class FileSharePlugin extends Plugin {
    private final Map<String, Uri> pickedUris = new HashMap<>();
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
            JSArray files = describePickedFiles(result.getData());
            JSObject response = new JSObject();
            response.put("files", files);
            call.resolve(response);
        } catch (Exception e) {
            call.reject("Could not read the selected file: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void readFileChunk(PluginCall call) {
        String fileId = call.getString("fileId");
        Integer offsetValue = call.getInt("offset");
        Integer lengthValue = call.getInt("length");
        if (fileId == null || offsetValue == null || lengthValue == null || lengthValue < 1 || lengthValue > 262144) {
            call.reject("Invalid file chunk request");
            return;
        }
        Uri uri = pickedUris.get(fileId);
        if (uri == null) {
            call.reject("Selected file is no longer available. Please select it again.");
            return;
        }
        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("Unable to open selected file");
            long remaining = offsetValue;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped <= 0) {
                    if (input.read() == -1) break;
                    skipped = 1;
                }
                remaining -= skipped;
            }
            byte[] buffer = new byte[lengthValue];
            int total = 0;
            while (total < buffer.length) {
                int count = input.read(buffer, total, buffer.length - total);
                if (count == -1) break;
                total += count;
            }
            byte[] chunk = new byte[total];
            System.arraycopy(buffer, 0, chunk, 0, total);
            JSObject result = new JSObject();
            result.put("base64", Base64.encodeToString(chunk, Base64.NO_WRAP));
            result.put("bytesRead", total);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Could not read selected file: " + e.getMessage(), e);
        }
    }

    private JSArray describePickedFiles(Intent intent) throws Exception {
        Set<Uri> uris = new LinkedHashSet<>();
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        if (intent.getData() != null) uris.add(intent.getData());
        JSArray files = new JSArray();
        for (Uri uri : uris) {
            String name = "selected-file";
            String mime = getContext().getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            long size = 0;
            try (Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameColumn >= 0) name = cursor.getString(nameColumn);
                    int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
                }
            }
            String fileId = UUID.randomUUID().toString();
            pickedUris.put(fileId, uri);
            JSObject file = new JSObject();
            file.put("fileId", fileId);
            file.put("name", name);
            file.put("type", mime);
            file.put("size", size);
            files.put(file);
        }
        return files;
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