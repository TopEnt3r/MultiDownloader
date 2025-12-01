package com.topent3r.multi.m3u.services;

import com.topent3r.multi.m3u.models.Channel;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class M3UParser {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .protocols(java.util.List.of(Protocol.HTTP_1_1))
            .build();

    /** Scarica il testo M3U replicando header "browser-like" */
    public String download(String url) throws IOException {
        HttpUrl httpUrl = HttpUrl.parse(url);
        if (httpUrl == null) throw new IOException("URL non valida: " + url);

        String host = httpUrl.host();
        String referer = httpUrl.scheme() + "://" + host + "/";

        Request req = new Request.Builder()
                .url(httpUrl)
                .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127 Safari/537.36")
                .header("Accept","*/*")
                .header("Accept-Language","it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding","gzip, deflate")
                .header("Cache-Control","no-cache")
                .header("Pragma","no-cache")
                .header("Referer", referer)
                .header("Origin", referer)
                .header("Host", host)
                .header("Connection","keep-alive")
                // eventuali cookie "finti" per host "sensibili"
                .header("Cookie","PHPSESSID=fake12345; xtreamiptv=ok")
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body()==null) {
                throw new IOException("HTTP " + resp.code() + " durante il download M3U");
            }
            return resp.body().string();
        }
    }

    /** Parse del contenuto M3U in lista di Channel */
    public List<Channel> parse(String m3uText) {
        List<Channel> out = new ArrayList<>();
        if (m3uText == null || m3uText.isBlank()) return out;

        String name = "", group = "", tvgId = "", logo = "";
        for (String raw : m3uText.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF:")) {
                name = group = tvgId = logo = "";

                int idx = line.indexOf(',');
                if (idx >= 0 && idx + 1 < line.length()) {
                    name = line.substring(idx + 1).trim();
                }

                tvgId = extractAttr(line, "tvg-id");
                logo  = extractAttr(line, "tvg-logo");
                group = extractAttr(line, "group-title");
            } else if (line.startsWith("http://") || line.startsWith("https://")) {
                boolean isVod = looksLikeVod(name, line);
                String id = buildId(tvgId, name, line);
                out.add(new Channel(id, name, group, line, tvgId, logo, isVod));
            }
        }
        return out;
    }

    private static String extractAttr(String line, String key) {
        String pat = key + "=\"";
        int i = line.indexOf(pat);
        if (i < 0) return "";
        int j = line.indexOf('"', i + pat.length());
        return (j > i) ? line.substring(i + pat.length(), j).trim() : "";
    }

    /** Heuristics: usa tvg-id se numerico, altrimenti le ultime cifre nell'URL prima dell'estensione. */
    private static String buildId(String tvgId, String name, String url) {
        if (tvgId != null && tvgId.matches("\\d+")) return tvgId;

        // prova a prendere l'ultima sequenza di 4+ cifre dall'URL (es. .../123456.mp4)
        if (url != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{4,})(?:\\.(?:mp4|ts|mkv|m3u8))?$", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(url);
            if (m.find()) return m.group(1);
        }

        // fallback: hash breve dal nome+url (stabile, ma non numerico)
        String base = (name == null ? "" : name) + "|" + (url == null ? "" : url);
        return Integer.toUnsignedString(base.hashCode()); // stringa numerica
    }

    private static boolean looksLikeVod(String name, String url) {
        String n = name == null ? "" : name.toLowerCase();
        String u = url == null ? "" : url.toLowerCase();
        return n.contains("vod") || n.contains("movie") || n.contains("film")
                || u.contains("/movie/") || u.contains("/vod/") || u.endsWith(".mp4");
    }
}
