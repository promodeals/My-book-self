package com.ssk.bookvault;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends BridgeActivity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String pendingScript = null;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    handleShare(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleShare(intent);
  }

  private void handleShare(Intent intent) {
    if (intent == null) return;
    String action = intent.getAction();
    if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
    Uri uri = null;
    if (Intent.ACTION_SEND.equals(action)) {
      Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
      if (stream instanceof Uri) uri = (Uri) stream;
    } else {
      java.util.ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (streams != null && !streams.isEmpty()) uri = streams.get(0);
    }
    if (uri == null) return;
    final Uri sharedUri = uri;
    new Thread(() -> {
      try (InputStream in = getContentResolver().openInputStream(sharedUri);
           ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        if (in == null) return;
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
          out.write(buf, 0, n);
          if (out.size() > 40 * 1024 * 1024) return;
        }
        String mime = getContentResolver().getType(sharedUri);
        String name = "shared-document";
        android.database.Cursor cursor = getContentResolver().query(sharedUri, null, null, null, null);
        if (cursor != null) {
          try {
            int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx);
          } finally { cursor.close(); }
        }
        String dataUrl = "data:" + ((mime == null || mime.isEmpty()) ? "application/octet-stream" : mime)
          + ";base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        JSONObject detail = new JSONObject();
        detail.put("name", name);
        detail.put("mimeType", mime == null ? "application/octet-stream" : mime);
        detail.put("dataUrl", dataUrl);
        pendingScript = "window.dispatchEvent(new CustomEvent('native-shared-file',{detail:" + detail.toString() + "}));";
        deliverPending(0);
      } catch (Exception ignored) { }
    }).start();
  }

  private void deliverPending(int attempt) {
    handler.postDelayed(() -> {
      if (pendingScript == null) return;
      try {
        if (bridge != null && bridge.getWebView() != null) {
          WebView view = bridge.getWebView();
          view.evaluateJavascript(pendingScript, null);
          pendingScript = null;
          return;
        }
      } catch (Exception ignored) { }
      if (attempt < 30) deliverPending(attempt + 1);
    }, attempt == 0 ? 700 : 500);
  }
}