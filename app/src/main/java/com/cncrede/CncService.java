package com.cncrede;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.security.SecureRandom;
import java.util.Locale;

public class CncService extends Service {
    public static volatile CncService instance;
    public SerialLink link;
    public String pin;
    private Server server;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        SharedPreferences sp = getSharedPreferences("cnc", MODE_PRIVATE);
        pin = sp.getString("pin", null);
        if (pin == null) {
            pin = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
            sp.edit().putString("pin", pin).apply();
        }
        link = new SerialLink(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("cnc", "CNC em rede", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "cnc");
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = b.setContentTitle("CNC em rede ativa")
                .setContentText("Servidor na porta " + Server.PORT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);

        if (server == null) {
            try {
                server = new Server(this, link, pin);
                server.start();
            } catch (Exception e) {
                link.message = "Erro ao iniciar servidor: " + e.getMessage();
                server = null;
            }
        }
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cncrede:wake");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cncrede:wifi");
            wifi.acquire();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        link.shutdown();
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        instance = null;
        super.onDestroy();
    }
}
