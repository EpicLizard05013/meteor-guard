package com.meteorsmp.antiesp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Sends alerts to a Discord channel through a webhook (async, rate limited, never pings @everyone). */
final class DiscordWebhook {

    private final AntiESPPlugin plugin;
    private final HttpClient http = HttpClient.newHttpClient();
    private volatile boolean enabled;
    private volatile String url = "";
    private volatile String username = "MeteorGuard";
    private long windowStart;
    private int sentInWindow;

    DiscordWebhook(AntiESPPlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("discord-webhook.enabled", false);
        url = c.getString("discord-webhook.url", "");
        username = c.getString("discord-webhook.username", "MeteorGuard");
    }

    synchronized void send(String text) {
        String target = url;
        if (!enabled || target == null || target.isBlank() || !target.startsWith("https://")) return;

        long now = System.currentTimeMillis();
        if (now - windowStart > 60_000L) {
            windowStart = now;
            sentInWindow = 0;
        }
        if (++sentInWindow > 20) return; // Discord allows about 30 per minute; stay below it

        if (text.length() > 1800) text = text.substring(0, 1800);
        String body = "{\"username\":\"" + esc(username) + "\",\"content\":\"" + esc(text)
                + "\",\"allowed_mentions\":{\"parse\":[]}}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(ex -> null);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("discord-webhook.url is not a valid URL.");
        }
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '\t': sb.append(' '); break;
                default:
                    if (ch < 0x20) sb.append(' ');
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
