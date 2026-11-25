package com.cncrede;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {
    private TextView info;
    private WebView web;
    private boolean loaded;
    private ValueCallback<Uri[]> chooser;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            final CncService s = CncService.instance;
            if (s == null) {
                info.setText("Servidor parado");
                loaded = false;
            } else {
                info.setText("http://" + ip() + ":" + Server.PORT + "   PIN: " + s.pin);
                if (!loaded) {
                    loaded = true;
                    h.postDelayed(() -> web.loadUrl("http://127.0.0.1:" + Server.PORT + "/?pin=" + s.pin), 800);
                }
            }
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);

        info = new TextView(this);
        info.setTextSize(13);
        info.setPadding(pad, pad, pad, 0);
        root.addView(info);

        LinearLayout bar = new LinearLayout(this);
        Button toggle = new Button(this);
        toggle.setText("Servidor");
        toggle.setOnClickListener((View v) -> {
            if (CncService.instance != null) stopService(new Intent(this, CncService.class));
            else startSvc();
        });
        Button reload = new Button(this);
        reload.setText("Recarregar");
        reload.setOnClickListener((View v) -> loaded = false);
        bar.addView(toggle, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(reload, new LinearLayout.LayoutParams(0, -2, 1f));
        Button upd = new Button(this);
        upd.setText("Atualizar");
        upd.setOnClickListener((View v) -> UpdateChecker.check(this, true));
        bar.addView(upd, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try { startActivityForResult(p.createIntent(), 7); }
                catch (Exception e) { chooser = null; return false; }
                return true;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        startSvc();
        UpdateChecker.check(this, false);
        usbIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); usbIntent(i); }

    /** Conecta sozinho quando o cabo USB da CNC é plugado. */
    private void usbIntent(Intent i) {
        if (i == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) return;
        new Thread(() -> {
            try { for (int n = 0; n < 10 && CncService.instance == null; n++) Thread.sleep(300); } catch (Exception ignored) {}
            CncService s = CncService.instance;
            if (s != null) s.link.connect(115200);
        }).start();
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == 7 && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, d));
            chooser = null;
        }
    }

    private void startSvc() {
        Intent i = new Intent(this, CncService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    private String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "IP-do-celular";
    }
}
