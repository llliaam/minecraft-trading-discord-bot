package com.smp.marketplace.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.smp.marketplace.SmpMarketMod;
import com.smp.marketplace.config.ModConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Klien WebSocket ke bot Node — menerima event push notifikasi.
 *
 * <p>Mod membuka satu koneksi WS saat startup dan mengirimkan event ke
 * {@link #onEvent} (dipasang oleh {@link com.smp.marketplace.SmpMarketMod}).
 * Koneksi putus → reconnect otomatis dengan backoff eksponensial (1s → 2s → 4s …
 * maks 60s). Berhenti saat {@link #shutdown()} dipanggil.
 *
 * <p>Event yang diterima (JSON):
 * <pre>
 *   { "event": "OFFER_RECEIVED",  "data": { "targetUuid": "...", ... } }
 *   { "event": "OFFER_ACCEPTED",  "data": { "targetUuid": "...", ... } }
 *   { "event": "OFFER_REJECTED",  "data": { "targetUuid": "...", ... } }
 *   { "event": "ITEM_SOLD",       "data": { "targetUuid": "...", ... } }
 *   { "event": "LISTING_EXPIRED", "data": { "targetUuid": "...", ... } }
 * </pre>
 */
public class WsClient {
    private static final long BACKOFF_INITIAL_MS = 1_000;
    private static final long BACKOFF_MAX_MS     = 60_000;

    private final ModConfig config;
    private final HttpClient http;
    private final Gson gson = new Gson();

    /** Callback: dipanggil tiap event masuk di server thread. */
    private BiConsumer<String, JsonObject> onEvent;

    private volatile boolean stopped = false;
    private volatile WebSocket ws;

    public WsClient(ModConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /** Pasang handler event (dipanggil sebelum {@link #start()}). */
    public void setOnEvent(BiConsumer<String, JsonObject> handler) {
        this.onEvent = handler;
    }

    /** Mulai koneksi di thread daemon; reconnect otomatis bila putus. */
    public void start() {
        Thread t = new Thread(this::connectLoop, "smpmarket-ws");
        t.setDaemon(true);
        t.start();
    }

    /** Hentikan koneksi dan reconnect loop secara permanen. */
    public void shutdown() {
        stopped = true;
        WebSocket w = ws;
        if (w != null) {
            w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").exceptionally(e -> null);
        }
    }

    // ─── Koneksi ─────────────────────────────────────────────────────────────

    private void connectLoop() {
        long backoff = BACKOFF_INITIAL_MS;
        while (!stopped) {
            try {
                SmpMarketMod.LOGGER.info("WS: menyambung ke bot...");
                String wsUrl = config.apiBase
                    .replace("http://", "ws://")
                    .replace("https://", "wss://");

                ws = http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + config.apiSecret)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), new Listener())
                    .get(15, TimeUnit.SECONDS);

                SmpMarketMod.LOGGER.info("WS: terhubung ke bot.");
                backoff = BACKOFF_INITIAL_MS; // reset backoff setelah berhasil

                // Tunggu sampai Listener menandai koneksi selesai.
                synchronized (this) {
                    while (!stopped && ws != null && !ws.isInputClosed()) {
                        wait(5_000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                SmpMarketMod.LOGGER.warn("WS: gagal terhubung — {}. Coba lagi dalam {}s.",
                    e.getMessage(), backoff / 1000);
            }

            if (!stopped) {
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { break; }
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
            }
        }
        SmpMarketMod.LOGGER.info("WS: client berhenti.");
    }

    // ─── WebSocket.Listener ───────────────────────────────────────────────────

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String raw = buf.toString();
                buf.setLength(0);
                handleMessage(raw);
            }
            return WebSocket.Listener.super.onText(ws, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            SmpMarketMod.LOGGER.info("WS: koneksi ditutup ({}) — {}", statusCode, reason);
            WsClient.this.ws = null;
            synchronized (WsClient.this) { WsClient.this.notifyAll(); }
            return WebSocket.Listener.super.onClose(ws, statusCode, reason);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            SmpMarketMod.LOGGER.warn("WS error: {}", error.getMessage());
            WsClient.this.ws = null;
            synchronized (WsClient.this) { WsClient.this.notifyAll(); }
        }
    }

    // ─── Parsing event ────────────────────────────────────────────────────────

    private void handleMessage(String raw) {
        try {
            JsonObject obj = gson.fromJson(raw, JsonObject.class);
            if (obj == null || !obj.has("event") || !obj.has("data")) return;
            String event = obj.get("event").getAsString();
            JsonObject data = obj.getAsJsonObject("data");
            if (onEvent != null) onEvent.accept(event, data);
        } catch (Exception e) {
            SmpMarketMod.LOGGER.warn("WS: gagal parse pesan — {}", e.getMessage());
        }
    }
}
