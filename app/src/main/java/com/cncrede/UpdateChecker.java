package com.cncrede;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Procura uma versão nova nos Releases do GitHub, baixa o APK e abre o instalador. */
public class UpdateChecker {
    static final String REPO = "Ronaldo-junio/Cnc-control";

    public static void check(final Activity a, final boolean manual) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(new String(read("https://api.github.com/repos/" + REPO + "/releases/latest")));
                final int remote = Integer.parseInt(j.getString("tag_name").replaceAll("\\D", ""));
                final int local = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
                final String url = j.getJSONArray("assets").getJSONObject(0).getString("browser_download_url");
                if (remote <= local) {
                    if (manual) toast(a, "Você já está na versão mais recente (" + local + ")");
                    return;
                }
                if (a.isFinishing()) return;
                a.runOnUiThread(() -> new AlertDialog.Builder(a)
                        .setTitle("Atualização disponível")
                        .setMessage("Nova versão " + remote + " (instalada: " + local + "). Baixar e instalar?")
                        .setPositiveButton("Atualizar", (d, w) -> download(a, url))
                        .setNegativeButton("Depois", null).show());
            } catch (Exception e) {
                if (manual) toast(a, "Não consegui verificar: " + e.getMessage());
            }
        }, "update-check").start();
    }

    private static void download(final Activity a, final String url) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            toast(a, "Permita instalar apps por este aplicativo e toque em Atualizar de novo");
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        toast(a, "Baixando atualização...");
        new Thread(() -> {
            try {
                File dir = new File(a.getCacheDir(), "updates");
                dir.mkdirs();
                File f = new File(dir, "CncRede.apk");
                byte[] data = read(url);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                a.runOnUiThread(() -> {
                    Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", f);
                    Intent i = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(u, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(i);
                });
            } catch (Exception e) {
                toast(a, "Falha ao baixar: " + e.getMessage());
            }
        }, "update-download").start();
    }

    private static byte[] read(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "CncRede");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void toast(final Activity a, final String m) {
        a.runOnUiThread(() -> Toast.makeText(a, m, Toast.LENGTH_LONG).show());
    }
}
