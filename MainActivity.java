package com.losprimos.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.sunmi.peripheral.printer.InnerPrinterCallback;
import com.sunmi.peripheral.printer.InnerPrinterManager;
import com.sunmi.peripheral.printer.SunmiPrinterService;

/** Los Primos POS dentro de una app: el mismo POS web + impresión directa en la impresora interna de la Sunmi. */
public class MainActivity extends Activity {
    private static final String URL = "https://pososcuro.netlify.app/posnuevo/";
    private static final String HOST = "pososcuro.netlify.app";
    private static final int REQ_FILE = 1;

    private WebView web;
    private volatile SunmiPrinterService printer;
    private ValueCallback<Uri[]> filePending;

    private final InnerPrinterCallback printerCb = new InnerPrinterCallback() {
        @Override protected void onConnected(SunmiPrinterService service) { printer = service; }
        @Override protected void onDisconnected() { printer = null; }
    };

    /** Lo que el POS ve como window.SunmiBridge */
    private class Bridge {
        @JavascriptInterface public boolean disponible() { return printer != null; }

        @JavascriptInterface public boolean imprimir(String base64) {
            SunmiPrinterService p = printer;
            if (p == null) { bindPrinter(); return false; }
            try {
                byte[] datos = Base64.decode(base64, Base64.DEFAULT);
                p.sendRAWData(datos, null);
                p.lineWrap(3, null); // avanza el papel para poder rasgar
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private void bindPrinter() {
        try { InnerPrinterManager.getInstance().bindService(this, printerCb); } catch (Exception ignored) { }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#14110f"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "SunmiBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePending != null) filePending.onReceiveValue(null);
                filePending = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); }
                catch (Exception e) { filePending = null; return false; }
                return true;
            }
            @Override public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                        .setPositiveButton("OK", (d, i) -> r.confirm()).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                        .setPositiveButton("OK", (d, i) -> r.confirm())
                        .setNegativeButton("Cancelar", (d, i) -> r.cancel()).show();
                return true;
            }
        });

        bindPrinter();
        web.loadUrl(URL);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && filePending != null) {
            filePending.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            filePending = null;
        } else super.onActivityResult(req, res, data);
    }

    @Override public void onBackPressed() { moveTaskToBack(true); } // no cerrar el POS por accidente

    @Override protected void onResume() { super.onResume(); if (printer == null) bindPrinter(); }

    @Override protected void onDestroy() {
        try { InnerPrinterManager.getInstance().unBindService(this, printerCb); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
