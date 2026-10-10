package com.promodeals.mybookshelf;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.json.JSONObject;

public class MainActivity extends Activity {
  private WebView web;
  private String pendingPayload = null;
  private static final String APP_URL = "https://promodeals.github.io/My-book-self/";
  @Override public void onCreate(Bundle state) { super.onCreate(state); web = new WebView(this); web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true); web.setWebChromeClient(new WebChromeClient()); web.setWebViewClient(new WebViewClient(){ @Override public void onPageFinished(WebView view,String url){ deliverPending(); }}); web.addJavascriptInterface(this,"BookshelfAndroid"); setContentView(web); handleIntent(getIntent()); web.loadUrl(APP_URL); }
  @Override protected void onNewIntent(Intent intent){ super.onNewIntent(intent); setIntent(intent); handleIntent(intent); deliverPending(); }
  private void handleIntent(Intent intent){ if(intent==null)return; String action=intent.getAction(); try { if(Intent.ACTION_SEND.equals(action)){ Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM); if(uri!=null) pendingPayload=encode(uri,intent.getType()); else {String text=intent.getStringExtra(Intent.EXTRA_TEXT); if(text!=null) pendingPayload=makePayload(text,"text/plain",text.getBytes("UTF-8"));} } else if(Intent.ACTION_SEND_MULTIPLE.equals(action)){ java.util.ArrayList<Uri> uris=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if(uris!=null&&!uris.isEmpty())pendingPayload=encode(uris.get(0),intent.getType()); } } catch(Exception e){ pendingPayload=null; } }
  private String encode(Uri uri,String mime)throws Exception { InputStream in=getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>0){if(out.size()+n>30*1024*1024)throw new Exception("File too large");out.write(buf,0,n);} in.close(); String name="Shared file"; android.database.Cursor c=getContentResolver().query(uri,null,null,null,null); if(c!=null){int ix=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);if(c.moveToFirst()&&ix>=0)name=c.getString(ix);c.close();} return makePayload(name,mime,out.toByteArray()); }
  private String makePayload(String name,String mime,byte[] bytes)throws Exception { JSONObject o=new JSONObject();o.put("name",name);o.put("type",mime==null?"application/octet-stream":mime);o.put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP));return o.toString(); }
  private void deliverPending(){ if(web==null||pendingPayload==null)return; final String payload=pendingPayload; pendingPayload=null; web.postDelayed(()->web.evaluateJavascript("(function(){if(window.receiveNativeSharedFile){window.receiveNativeSharedFile("+JSONObject.quote(payload)+");}})()",null),500); }
  @JavascriptInterface public void closeApp(){runOnUiThread(this::finish);}
  @Override public void onBackPressed() {
    if (web == null) {
      super.onBackPressed();
      return;
    }
    // Let the PDF reader consume Android Back first, closing the reader overlay
    // and returning to the bookshelf instead of navigating away from the app.
    web.evaluateJavascript(
      "(function(){return window.handleNativeBack && window.handleNativeBack() ? 'handled' : 'not-handled';})()",
      result -> {
        if (!"\"handled\"".equals(result)) {
          if (web != null && web.canGoBack()) {
            web.goBack();
          } else {
            MainActivity.super.onBackPressed();
          }
        }
      }
    );
  }
}