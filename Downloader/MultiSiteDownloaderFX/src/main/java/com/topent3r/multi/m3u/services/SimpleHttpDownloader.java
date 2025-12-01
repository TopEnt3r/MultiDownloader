package com.topent3r.multi.m3u.services;

import okhttp3.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Downloader HTTP semplice per server IPTV che non tollerano Range requests o multi-chunk.
 * Usa un singolo download sequenziale con buffer ottimizzato.
 */
public class SimpleHttpDownloader {

    private final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .protocols(java.util.List.of(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)  // Timeout lungo per file grandi
            .build();

    public interface ProgressCallback {
        void onProgress(String message);
        void onSpeed(String speed);
    }

    /**
     * Download singolo senza Range requests - ideale per server IPTV restrittivi.
     */
    public Path download(String url, Path dir, String fileName, Map<String,String> headers) throws IOException {
        return download(url, dir, fileName, headers, null);
    }

    public Path download(String url, Path dir, String fileName, Map<String,String> headers, ProgressCallback callback) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("URL vuota");
        if (dir == null) dir = Paths.get(System.getProperty("user.home"), "Downloads");
        Files.createDirectories(dir);

        fileName = sanitize(fileName);
        if (fileName.isBlank()) fileName = "stream.mp4";

        Path out = unique(dir.resolve(fileName));
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".part");

        // Build request con headers semplici
        Request.Builder rb = new Request.Builder().url(url);
        if (headers != null) {
            headers.forEach((k, v) -> {
                // NON aggiungere Range header per evitare HTTP 400
                if (!"Range".equalsIgnoreCase(k)) {
                    rb.header(k, v);
                }
            });
        }

        System.out.println("[SimpleDownload] Downloading: " + url);
        System.out.println("[SimpleDownload] Output: " + out);

        try (Response resp = client.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code() + " su " + url);
            }

            // Download con buffer 2MB per velocità ottimale
            try (InputStream in = resp.body().byteStream();
                 OutputStream fos = new BufferedOutputStream(Files.newOutputStream(tmp), 2 * 1024 * 1024)) {
                
                byte[] buffer = new byte[2 * 1024 * 1024]; // 2MB buffer
                int bytesRead;
                long totalBytes = 0;
                long lastLog = System.currentTimeMillis();
                long lastSpeedUpdate = System.currentTimeMillis();
                long bytesAtLastSpeedUpdate = 0;
                
                while ((bytesRead = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                    
                    long now = System.currentTimeMillis();
                    
                    // Update speed ogni 2 secondi
                    if (callback != null && now - lastSpeedUpdate > 2000) {
                        long elapsed = now - lastSpeedUpdate;
                        long bytesDownloaded = totalBytes - bytesAtLastSpeedUpdate;
                        double speedMBps = (bytesDownloaded / 1024.0 / 1024.0) / (elapsed / 1000.0);
                        callback.onSpeed(String.format("%.2f MB/s", speedMBps));
                        
                        lastSpeedUpdate = now;
                        bytesAtLastSpeedUpdate = totalBytes;
                    }
                    
                    // Log progress ogni 5 secondi
                    if (now - lastLog > 5000) {
                        System.out.println("[SimpleDownload] Downloaded: " + formatSize(totalBytes));
                        lastLog = now;
                    }
                }
                
                System.out.println("[SimpleDownload] Total: " + formatSize(totalBytes));
            }
        }

        long size = Files.exists(tmp) ? Files.size(tmp) : 0;
        if (size <= 0) throw new IOException("File risultante vuoto.");
        
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("[SimpleDownload] SUCCESS: " + out);
        return out;
    }

    private String sanitize(String name) {
        if (name == null || name.isBlank()) return "stream";
        return name.replaceAll("[^a-zA-Z0-9._() -]", "_");
    }

    private Path unique(Path p) {
        if (!Files.exists(p)) return p;
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            Path np = p.resolveSibling(base + "_" + i + ext);
            if (!Files.exists(np)) return np;
        }
        return p;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
