package com.cncrede;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Conexão USB serial com a CNC (protocolo GRBL: uma linha por vez, aguarda "ok"). */
public class SerialLink implements SerialInputOutputManager.Listener {
    static final String ACTION_USB = "com.cncrede.USB_PERMISSION";

    private final Context ctx;
    private final UsbManager usb;
    private UsbSerialPort port;
    private SerialInputOutputManager io;
    private int baud = 115200;

    public volatile String message = "Desconectado";
    public volatile String lastStatus = "";

    private final StringBuilder rx = new StringBuilder();
    private final List<String> log = new ArrayList<>();
    private int logBase = 0;
    private final BlockingQueue<String> acks = new LinkedBlockingQueue<>();

    private volatile boolean jobRunning, paused, abort;
    private volatile int jobLine, jobTotal;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    private final BroadcastReceiver rcv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_USB.equals(a)) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openFirst();
                else message = "Permissão USB negada";
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                disconnect();
                message = "Cabo USB removido";
            }
        }
    };

    public SerialLink(Context c) {
        ctx = c.getApplicationContext();
        usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        IntentFilter f = new IntentFilter(ACTION_USB);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(rcv, f);
        // pede o status da máquina ("?") duas vezes por segundo
        poller.scheduleWithFixedDelay(() -> {
            try { if (isConnected() && grbl) realtime((byte) '?'); } catch (Exception ignored) {}
        }, 1, 500, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        disconnect();
        poller.shutdownNow();
        try { ctx.unregisterReceiver(rcv); } catch (Exception ignored) {}
    }

    public boolean isConnected() { return port != null && port.isOpen(); }
    public boolean isJobRunning() { return jobRunning; }
    public boolean isPaused() { return paused; }
    public int getJobLine() { return jobLine; }
    public int getJobTotal() { return jobTotal; }

    private volatile Thread jobThread;
    private volatile boolean grbl = true;
    private volatile int rxLimit = 120;

    /** true = GRBL (buffer cheio, polling de status); false = outro firmware (linha a linha). */
    public void setGrbl(boolean g) { grbl = g; rxLimit = g ? 120 : 0; }

    // ---------- conexão ----------
    public synchronized String connect(int b) {
        baud = b;
        if (isConnected()) return "Já conectado";
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb);
        if (drivers.isEmpty()) { message = "Nenhum dispositivo USB serial encontrado"; return message; }
        UsbDevice dev = drivers.get(0).getDevice();
        if (!usb.hasPermission(dev)) {
            Intent in = new Intent(ACTION_USB).setPackage(ctx.getPackageName());
            int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, in, fl));
            message = "Aguardando permissão USB no celular (toque em OK)";
            return message;
        }
        return openFirst();
    }

    private synchronized String openFirst() {
        try {
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb);
            if (drivers.isEmpty()) { message = "Nenhum dispositivo USB serial encontrado"; return message; }
            UsbSerialDriver d = drivers.get(0);
            UsbDeviceConnection c = usb.openDevice(d.getDevice());
            if (c == null) { message = "Falha ao abrir o USB (sem permissão?)"; return message; }
            port = d.getPorts().get(0);
            port.open(c);
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            io = new SerialInputOutputManager(port, this);
            Executors.newSingleThreadExecutor().submit(io);
            message = "Conectado (" + baud + " baud)";
            addLog("== Conectado a " + d.getDevice().getDeviceName());
        } catch (Exception e) {
            message = "Erro ao conectar: " + e.getMessage();
            try { if (port != null) port.close(); } catch (Exception ignored) {}
            port = null;
        }
        return message;
    }

    public synchronized void disconnect() {
        abort = true;
        paused = false;
        Thread jt = jobThread;
        if (jt != null) jt.interrupt();
        try { if (io != null) io.stop(); } catch (Exception ignored) {}
        try { if (port != null) port.close(); } catch (Exception ignored) {}
        io = null;
        port = null;
        message = "Desconectado";
    }

    // ---------- envio ----------
    private void write(byte[] b) throws IOException {
        UsbSerialPort p = port;
        if (p == null) throw new IOException("Não conectado");
        p.write(b, 2000);
    }

    public void sendLine(String s) throws IOException {
        write((s + "\n").getBytes(StandardCharsets.US_ASCII));
        addLog(">> " + s);
    }

    public void realtime(byte b) throws IOException { write(new byte[]{b}); }

    // ---------- recepção ----------
    @Override public void onNewData(byte[] data) {
        synchronized (rx) {
            rx.append(new String(data, StandardCharsets.US_ASCII));
            int i;
            while ((i = rx.indexOf("\n")) >= 0) {
                String line = rx.substring(0, i).trim();
                rx.delete(0, i + 1);
                if (!line.isEmpty()) handle(line);
            }
        }
    }

    @Override public void onRunError(Exception e) {
        message = "Erro de leitura: " + e.getMessage();
        disconnect();
        message = "Conexão perdida: " + e.getMessage();
    }

    private void handle(String line) {
        if (line.startsWith("<")) { lastStatus = line; return; }
        addLog(line);
        if (line.startsWith("ALARM")) {
            if (jobRunning) { abort = true; Thread t = jobThread; if (t != null) t.interrupt(); }
            return;
        }
        if (line.startsWith("ok") || line.startsWith("error")) acks.offer(line);
    }

    // ---------- job (arquivo G-code) ----------
    public synchronized String startJob(String gcode) {
        if (!isConnected()) return "Não conectado";
        if (jobRunning) return "Já existe um job em execução";
        final List<String> lines = new ArrayList<>();
        for (String raw : gcode.split("\\r?\\n")) {
            String s = raw.replaceAll("\\(.*?\\)", "").replaceAll(";.*$", "").trim();
            if (s.length() > 110) return "Linha longa demais (máx. 110 caracteres): " + s.substring(0, 30) + "...";
            if (!s.isEmpty()) lines.add(s);
        }
        if (lines.isEmpty()) return "Arquivo vazio";
        acks.clear();
        abort = false;
        paused = false;
        jobTotal = lines.size();
        jobLine = 0;
        jobRunning = true;
        jobThread = new Thread(() -> {
            java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();
            int used = 0, sent = 0;
            try {
                while (!abort && jobLine < jobTotal) {
                    while (paused && !abort) Thread.sleep(100);
                    // enche o buffer da CNC (contagem de caracteres) em vez de esperar cada "ok"
                    while (!paused && sent < jobTotal) {
                        int n = lines.get(sent).length() + 1;
                        if (!pending.isEmpty() && used + n > rxLimit) break;
                        sendLine(lines.get(sent));
                        pending.add(n);
                        used += n;
                        sent++;
                    }
                    String r = acks.poll(120, TimeUnit.SECONDS);
                    if (r == null) { addLog("!! Timeout aguardando resposta da CNC"); break; }
                    if (!pending.isEmpty()) used -= pending.poll();
                    if (!r.startsWith("ok")) {
                        addLog("!! Job interrompido: " + r);
                        if (grbl) realtime((byte) '!');
                        break;
                    }
                    jobLine++;
                }
                if (!abort && jobLine == jobTotal) addLog("== Job concluído");
            } catch (InterruptedException e) {
                // parado pelo usuário, alarme ou cabo removido
            } catch (Exception e) {
                addLog("!! " + e.getMessage());
            } finally {
                jobRunning = false;
            }
        }, "gcode-job");
        jobThread.start();
        return "Job iniciado (" + lines.size() + " linhas)";
    }

    public void pauseJob() throws IOException { paused = true; if (grbl) realtime((byte) '!'); }
    public void resumeJob() throws IOException { if (grbl) realtime((byte) '~'); paused = false; }

    public void stopJob() throws IOException {
        abort = true;
        paused = false;
        Thread t = jobThread;
        if (t != null) t.interrupt();
        if (grbl) {
            realtime((byte) '!');
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            realtime((byte) 0x18); // soft reset
        } else {
            sendLine("M410"); // parada rápida (Marlin)
        }
        acks.clear();
    }

    // ---------- log ----------
    private void addLog(String s) {
        synchronized (log) {
            log.add(s);
            if (log.size() > 500) { log.remove(0); logBase++; }
        }
    }

    /** Retorna as linhas de log a partir do índice "since"; out[0] recebe o próximo índice. */
    public List<String> getLog(int since, int[] next) {
        synchronized (log) {
            int from = Math.max(since, logBase) - logBase;
            if (from > log.size()) from = 0;
            next[0] = logBase + log.size();
            return new ArrayList<>(log.subList(from, log.size()));
        }
    }
}
