package com.cncrede;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Servidor HTTP: serve a interface web e a API REST que controla a CNC. */
public class Server extends NanoHTTPD {
    public static final int PORT = 8080;
    private final Context ctx;
    private final SerialLink link;
    private final String pin;

    public Server(Context c, SerialLink l, String pin) {
        super(PORT);
        this.ctx = c;
        this.link = l;
        this.pin = pin;
    }

    @Override
    public Response serve(IHTTPSession s) {
        String uri = s.getUri();
        try {
            if (uri.equals("/") || uri.equals("/index.html")) {
                return newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8",
                        ctx.getAssets().open("index.html"));
            }
            if (!uri.startsWith("/api/")) return json(Response.Status.NOT_FOUND, new JSONObject().put("error", "404"));

            String h = s.getHeaders().get("x-pin");
            if (!pin.equals(h)) return json(Response.Status.UNAUTHORIZED, new JSONObject().put("error", "PIN inválido"));

            Map<String, String> files = new HashMap<>();
            if (s.getMethod() == Method.POST || s.getMethod() == Method.PUT) s.parseBody(files);
            String body = files.get("postData");
            if (body == null) body = "";
            Map<String, String> p = s.getParms();

            JSONObject out = new JSONObject().put("ok", true);
            switch (uri) {
                case "/api/status": {
                    int since = 0;
                    try { since = Integer.parseInt(p.get("since")); } catch (Exception ignored) {}
                    int[] next = new int[1];
                    List<String> lines = link.getLog(since, next);
                    out.put("connected", link.isConnected());
                    out.put("msg", link.message);
                    out.put("status", link.lastStatus);
                    out.put("running", link.isJobRunning());
                    out.put("paused", link.isPaused());
                    out.put("line", link.getJobLine());
                    out.put("total", link.getJobTotal());
                    out.put("next", next[0]);
                    out.put("log", new JSONArray(lines));
                    break;
                }
                case "/api/connect": {
                    int baud = 115200;
                    try { baud = Integer.parseInt(p.get("baud")); } catch (Exception ignored) {}
                    link.setGrbl(!"simple".equals(p.get("mode")));
                    out.put("msg", link.connect(baud));
                    break;
                }
                case "/api/disconnect":
                    link.disconnect();
                    out.put("msg", "Desconectado");
                    break;
                case "/api/cmd": {
                    if (link.isJobRunning()) return json(Response.Status.CONFLICT, new JSONObject().put("error", "Job em execução"));
                    String cmd = body.trim();
                    if (cmd.isEmpty()) return json(Response.Status.BAD_REQUEST, new JSONObject().put("error", "Comando vazio"));
                    link.sendLine(cmd);
                    break;
                }
                case "/api/rt": {
                    String c = p.get("c");
                    byte b;
                    if ("hold".equals(c)) b = '!';
                    else if ("resume".equals(c)) b = '~';
                    else if ("reset".equals(c)) b = 0x18;
                    else if ("jogcancel".equals(c)) b = (byte) 0x85;
                    else b = '?';
                    link.realtime(b);
                    break;
                }
                case "/api/job/start": out.put("msg", link.startJob(body)); break;
                case "/api/job/pause": link.pauseJob(); break;
                case "/api/job/resume": link.resumeJob(); break;
                case "/api/job/stop": link.stopJob(); break;
                default:
                    return json(Response.Status.NOT_FOUND, new JSONObject().put("error", "rota desconhecida"));
            }
            return json(Response.Status.OK, out);
        } catch (Exception e) {
            try {
                return json(Response.Status.INTERNAL_ERROR, new JSONObject().put("error", String.valueOf(e.getMessage())));
            } catch (Exception ex) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "erro");
            }
        }
    }

    private Response json(Response.IStatus st, JSONObject o) {
        return newFixedLengthResponse(st, "application/json; charset=utf-8", o.toString());
    }
}
