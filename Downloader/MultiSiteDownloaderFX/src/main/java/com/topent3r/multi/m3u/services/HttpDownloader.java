package com.topent3r.multi.m3u.services;

import okhttp3.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class HttpDownloader {

    private static final Path LOG = Paths.get("download.log");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .protocols(java.util.List.of(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // stream lunghi
            .build();

    /** Download con autodetect HLS e fallback ffmpeg se il diretto fallisce (es. 403). */
    public Path download(String url, Path dir, String fileName, Map<String,String> headers) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("URL vuota");
        if (dir == null) dir = Paths.get(System.getProperty("user.home"), "Downloads");
        Files.createDirectories(dir);

        fileName = sanitize(fileName);
        if (fileName.isBlank()) fileName = "stream.mp4";

        boolean hls = looksLikeHls(url) || probeIsHls(url, headers);
        Path out = unique(dir.resolve(fileName));

        log("==> START url=" + url);
        log("hls=" + hls + " out=" + out);

        if (hls) {
            return downloadWithFfmpeg(url, out, headers);
        } else {
            try {
                return downloadDirect(url, out, headers);
            } catch (IOException e) {
                log("DIRECT FAIL: " + e.getMessage() + " → fallback ffmpeg");
                // fallback: prova a “copiare” anche i file diretti via ffmpeg con stessi header
                // Rimuovi header Range che può causare 400 Bad Request
                Map<String,String> cleanHeaders = new java.util.LinkedHashMap<>();
                if (headers != null) {
                    headers.forEach((k, v) -> {
                        if (!"Range".equalsIgnoreCase(k)) {
                            cleanHeaders.put(k, v);
                        }
                    });
                }
                Path tmp = out.getParent().resolve(out.getFileName().toString() + ".ff.tmp");
                Path p = downloadWithFfmpeg(url, tmp, cleanHeaders);
                // se ok, rinomina al nome finale
                Files.move(p, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return out;
            }
        }
    }

    // ---------- Direct (mp4/ts/bin) con multi-chunk parallelo ----------
    private Path downloadDirect(String url, Path out, Map<String,String> headers) throws IOException {
        Request.Builder rb = new Request.Builder().url(url);
        if (headers != null) headers.forEach(rb::header);
        
        // Verifica se il server supporta Range requests
        Request headReq = rb.head().build();
        long fileSize = -1;
        boolean supportsRange = false;
        
        try (Response headResp = client.newCall(headReq).execute()) {
            if (headResp.isSuccessful()) {
                String acceptRanges = headResp.header("Accept-Ranges");
                String contentLength = headResp.header("Content-Length");
                supportsRange = "bytes".equalsIgnoreCase(acceptRanges);
                if (contentLength != null) {
                    try { fileSize = Long.parseLong(contentLength); } catch (NumberFormatException ignore) {}
                }
            }
        } catch (Exception e) {
            log("HEAD request failed: " + e.getMessage());
        }
        
        // Se supporta Range E il file è grande (>10MB), usa download multi-chunk
        if (supportsRange && fileSize > 10_000_000) {
            return downloadMultiChunk(url, out, headers, fileSize);
        }
        
        // Altrimenti download singolo con buffer ottimizzato
        Request req = rb.build();
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".part");
        log("DIRECT -> " + out);
        
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code() + " su " + url);
            }
            
            // Buffer 1MB per velocità ottimale
            try (InputStream in = resp.body().byteStream();
                 OutputStream fos = new BufferedOutputStream(Files.newOutputStream(tmp), 1024 * 1024)) {
                byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
        }
        
        long size = Files.exists(tmp) ? Files.size(tmp) : 0;
        if (size <= 0) throw new IOException("File risultante vuoto.");
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log("DIRECT OK size=" + size);
        return out;
    }
    
    // Download multi-chunk parallelo (8 connessioni come JDownloader)
    private Path downloadMultiChunk(String url, Path out, Map<String,String> headers, long fileSize) throws IOException {
        int chunks = 8; // 8 connessioni parallele
        long chunkSize = fileSize / chunks;
        
        log("MULTI-CHUNK (" + chunks + " parts) -> " + out + " (size=" + fileSize + ")");
        
        Path tmpDir = out.getParent().resolve(out.getFileName().toString() + ".chunks");
        Files.createDirectories(tmpDir);
        
        List<java.util.concurrent.Future<Path>> futures = new ArrayList<>();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(chunks);
        
        try {
            for (int i = 0; i < chunks; i++) {
                final int chunkIndex = i;
                final long start = i * chunkSize;
                final long end = (i == chunks - 1) ? fileSize - 1 : (i + 1) * chunkSize - 1;
                
                futures.add(executor.submit(() -> {
                    Path chunkFile = tmpDir.resolve("chunk." + chunkIndex);
                    Request.Builder rb = new Request.Builder().url(url);
                    if (headers != null) headers.forEach(rb::header);
                    rb.header("Range", "bytes=" + start + "-" + end);
                    
                    try (Response resp = client.newCall(rb.build()).execute()) {
                        if (!resp.isSuccessful() || resp.body() == null) {
                            throw new IOException("Chunk " + chunkIndex + " failed: HTTP " + resp.code());
                        }
                        
                        try (InputStream in = resp.body().byteStream();
                             OutputStream fos = new BufferedOutputStream(Files.newOutputStream(chunkFile), 1024 * 1024)) {
                            byte[] buffer = new byte[1024 * 1024];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                    
                    log("Chunk " + chunkIndex + " OK");
                    return chunkFile;
                }));
            }
            
            // Attendi completamento tutti i chunk
            List<Path> chunkFiles = new ArrayList<>();
            for (java.util.concurrent.Future<Path> future : futures) {
                try {
                    chunkFiles.add(future.get());
                } catch (Exception e) {
                    throw new IOException("Chunk download failed: " + e.getMessage(), e);
                }
            }
            
            // Unisci i chunk in ordine
            Path tmp = out.resolveSibling(out.getFileName().toString() + ".part");
            try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(tmp), 1024 * 1024)) {
                for (int i = 0; i < chunks; i++) {
                    Path chunkFile = tmpDir.resolve("chunk." + i);
                    Files.copy(chunkFile, fos);
                }
            }
            
            // Cleanup chunks
            try {
                for (int i = 0; i < chunks; i++) {
                    Files.deleteIfExists(tmpDir.resolve("chunk." + i));
                }
                Files.deleteIfExists(tmpDir);
            } catch (Exception ignore) {}
            
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log("MULTI-CHUNK OK size=" + Files.size(out));
            return out;
            
        } finally {
            executor.shutdown();
        }
    }

    // ---------- Via FFmpeg (anche per file “diretti” in fallback) ----------
    private Path downloadWithFfmpeg(String url, Path out, Map<String,String> headers) throws IOException {
        return downloadWithFfmpeg(url, out, headers, null, 2);
    }

    public Path downloadWithFfmpeg(String url, Path out, Map<String,String> headers, ProgressCallback callback) throws IOException {
        return downloadWithFfmpeg(url, out, headers, callback, 2);
    }

    public Path downloadWithFfmpeg(String url, Path out, Map<String,String> headers, ProgressCallback callback, int speedMultiplier) throws IOException {
        String ffmpeg = findFfmpeg();
        if (ffmpeg == null) throw new IOException("FFmpeg non trovato (tools\\ffmpeg\\ffmpeg.exe o nel PATH)");

        String ua  = headers != null ? headers.getOrDefault("User-Agent", defaultUA()) : defaultUA();
        String ref = headers != null ? headers.getOrDefault("Referer", refererFor(url)) : refererFor(url);

        String headerBlock = buildFfmpegHeaderBlock(headers);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-loglevel"); cmd.add("info");
        cmd.add("-progress"); cmd.add("pipe:1");  // Output progresso su stdout
        
        // Per x1: velocità normale (5-8 MB/s come app Python)
        // Usa connessioni persistenti e richieste multiple per parallelizzare segmenti HLS
        if (speedMultiplier == 1) {
            cmd.add("-http_persistent"); cmd.add("1");           // Riusa connessioni HTTP
            cmd.add("-multiple_requests"); cmd.add("1");          // Abilita richieste multiple (download parallelo)
            cmd.add("-reconnect"); cmd.add("1");                  // Auto-reconnect
        }
        // Per x2-x4: opzioni più aggressive
        else if (speedMultiplier >= 2 && speedMultiplier < 8) {
            cmd.add("-http_persistent"); cmd.add("1");           // Riusa connessioni HTTP
            cmd.add("-multiple_requests"); cmd.add("1");          // Abilita richieste multiple
            cmd.add("-reconnect"); cmd.add("1");                  // Auto-reconnect
            if (speedMultiplier >= 4) {
                cmd.add("-http_seekable"); cmd.add("0");          // Non fare seek
                cmd.add("-fflags"); cmd.add("+genpts+igndts");    // Ignora timestamp
                cmd.add("-reconnect_streamed"); cmd.add("1");     // Reconnect per stream
                cmd.add("-reconnect_delay_max"); cmd.add("5");    // Max 5s ritardo
            }
        }
        // Per x8+: massima velocità possibile
        else if (speedMultiplier >= 8) {
            cmd.add("-http_persistent"); cmd.add("1");
            cmd.add("-multiple_requests"); cmd.add("1");
            cmd.add("-reconnect"); cmd.add("1");
            cmd.add("-http_seekable"); cmd.add("0");
            cmd.add("-fflags"); cmd.add("+genpts+igndts");
            cmd.add("-reconnect_streamed"); cmd.add("1");
            cmd.add("-reconnect_delay_max"); cmd.add("5");
            cmd.add("-thread_queue_size"); cmd.add("2048");       // Buffer grande
            cmd.add("-max_muxing_queue_size"); cmd.add("9999");   // Buffer muxing grande
        }
        
        cmd.add("-timeout"); cmd.add("10000000");             // 10s timeout
        cmd.add("-threads"); cmd.add("0");                    // Usa tutti i core CPU
        if (!headerBlock.isBlank()) { cmd.add("-headers"); cmd.add(headerBlock); }
        if (ua != null && !ua.isBlank()) { cmd.add("-user_agent"); cmd.add(ua); }
        if (ref != null && !ref.isBlank()) { cmd.add("-referer"); cmd.add(ref); }
        cmd.add("-i"); cmd.add(url);
        cmd.add("-c"); cmd.add("copy");                       // Stream copy (no transcode)
        cmd.add("-bsf:a"); cmd.add("aac_adtstoasc");
        cmd.add(out.toString());

        log("FFMPEG " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        long lastUpdate = System.currentTimeMillis();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                log("[ffmpeg] " + line);
                
                // Parse progresso FFmpeg (es: "out_time_ms=12345678")
                if (callback != null && line.startsWith("out_time_ms=")) {
                    try {
                        long micros = Long.parseLong(line.substring(12));
                        long secs = micros / 1_000_000;
                        
                        // Aggiorna ogni secondo
                        long now = System.currentTimeMillis();
                        if (now - lastUpdate > 1000) {
                            callback.onProgress(secs);
                            lastUpdate = now;
                        }
                    } catch (NumberFormatException ignore) {}
                } else if (callback != null && line.startsWith("speed=")) {
                    // Esempio: "speed=2.5x"
                    callback.onSpeed(line.substring(6).trim());
                }
            }
        } catch (Exception ignore) {}

        try {
            int code = p.waitFor();
            long size = Files.exists(out) ? Files.size(out) : 0;
            if (code != 0 || size == 0) {
                throw new IOException("FFmpeg failed (exit=" + code + "), size=" + size);
            }
            if (callback != null) callback.onComplete(size);
            log("FFMPEG OK size=" + size);
            return out;
        } catch (InterruptedException ie) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrotto.");
        }
    }

    public interface ProgressCallback {
        void onProgress(long seconds);
        void onSpeed(String speed);
        void onComplete(long bytes);
    }

    // ---------- Probe HLS ----------
    private boolean probeIsHls(String url, Map<String,String> headers) {
        try {
            Request.Builder rb = new Request.Builder().url(url);
            if (headers != null) headers.forEach(rb::header);
            rb.header("Range","bytes=0-2047");   // primi ~2KB
            try (Response r = client.newCall(rb.build()).execute()) {
                if (r.body() == null) return false;
                String ct = r.header("Content-Type", "");
                if (ct != null && (ct.contains("application/vnd.apple.mpegurl")
                                || ct.contains("vnd.apple.mpegurl")
                                || ct.contains("application/x-mpegURL")))
                    return true;
                String head = new String(r.body().bytes());
                return head.startsWith("#EXTM3U");
            }
        } catch (Exception e) {
            log("probe error: " + e.getMessage());
            return false;
        }
    }

    private static boolean looksLikeHls(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains(".m3u8");
    }

    // ---------- util ----------
    private static String sanitize(String s) { return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "_").trim(); }

    private static Path unique(Path p) throws IOException {
        if (!Files.exists(p)) return p;
        String name = p.getFileName().toString();
        String pref, ext;
        int dot = name.lastIndexOf('.');
        if (dot > 0) { pref = name.substring(0, dot); ext = name.substring(dot); }
        else { pref = name; ext = ""; }
        for (int i=1;;i++) {
            Path n = p.getParent().resolve(pref + " (" + i + ")" + ext);
            if (!Files.exists(n)) return n;
        }
    }

    private static String findFfmpeg() {
        Path local = Paths.get("tools", "ffmpeg", "ffmpeg.exe");
        if (Files.exists(local)) return local.toAbsolutePath().toString();
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) return "ffmpeg";
        } catch (Exception ignore) {}
        return null;
    }

    private static String buildFfmpegHeaderBlock(Map<String,String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        headers.forEach((k,v) -> { if (v != null && !v.isBlank()) sb.append(k).append(": ").append(v).append("\r\n"); });
        return sb.toString();
    }

    private static String refererFor(String url) {
        try { var u = new java.net.URI(url); return u.getScheme() + "://" + u.getHost() + "/"; }
        catch (Exception e) { return ""; }
    }
    private static String defaultUA() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127 Safari/537.36";
    }

    private static synchronized void log(String s) {
        try {
            Files.writeString(LOG, "["+new java.util.Date()+"] "+s+System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignore) {}
    }
}
