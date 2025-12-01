package com.topent3r.multi.services;

import com.topent3r.multi.model.Episode;
import com.topent3r.multi.model.MediaItem;
import com.topent3r.multi.m3u.models.Channel;
import com.topent3r.multi.m3u.services.SimpleHttpDownloader;

import java.nio.file.Path;
import java.util.*;

public class M3UProvider implements ContentProvider {
    
    private final SimpleHttpDownloader downloader = new SimpleHttpDownloader();
    private final String playlistUrl;
    private final Map<String, Channel> channelMap = new HashMap<>();
    
    public M3UProvider(String playlistUrl) {
        this.playlistUrl = playlistUrl;
    }
    
    public void registerChannel(Channel channel) {
        channelMap.put(channel.getId(), channel);
    }
    
    @Override
    public String getDisplayName() {
        return "M3U";
    }
    
    @Override
    public List<MediaItem> search(String query) throws Exception {
        // Non usato per M3U
        return Collections.emptyList();
    }
    
    @Override
    public List<Episode> listEpisodes(MediaItem item) throws Exception {
        // Per M3U, ogni canale è un singolo "episodio"
        Channel channel = channelMap.get(item.getId());
        if (channel == null) return Collections.emptyList();
        
        Episode ep = new Episode(channel.getId(), "1", "1", channel.getName());
        
        return Collections.singletonList(ep);
    }
    
    @Override
    public void download(MediaItem item, Episode episode, Path outputDir) throws Exception {
        download(item, episode, outputDir, null);
    }
    
    @Override
    public void download(MediaItem item, Episode episode, Path outputDir, DownloadCallback callback) throws Exception {
        Channel channel = channelMap.get(item.getId());
        if (channel == null) {
            System.err.println("Channel not found: " + item.getId());
            throw new Exception("Channel not found: " + item.getId());
        }
        
        String url = channel.getUrl();
        String fileName = sanitize(channel.getName()) + guessExt(url);
        
        Map<String, String> headers = buildHeaders(url);
        
        if (callback != null) {
            callback.onProgress("Scaricando " + fileName);
        }
        
        // Pass callback to downloader for speed updates
        downloader.download(url, outputDir, fileName, headers, new com.topent3r.multi.m3u.services.SimpleHttpDownloader.ProgressCallback() {
            @Override
            public void onProgress(String message) {
                if (callback != null) callback.onProgress(message);
            }
            
            @Override
            public void onSpeed(String speed) {
                if (callback != null) callback.onSpeed(speed);
            }
        });
        
        if (callback != null) {
            callback.onProgress("✅ Completato");
        }
    }
    
    private Map<String, String> buildHeaders(String url) {
        Map<String, String> headers = new LinkedHashMap<>();
        
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            String referer = scheme + "://" + host + "/";
            
            // Solo headers essenziali per evitare HTTP 400/520
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127 Safari/537.36");
            headers.put("Referer", referer);  // Usa referer semplice, non playlistUrl
            headers.put("Connection", "keep-alive");
        } catch (Exception e) {
            System.err.println("Failed to build headers: " + e.getMessage());
        }
        
        return headers;
    }
    
    private String sanitize(String name) {
        if (name == null || name.isBlank()) return "stream";
        return name.replaceAll("[^a-zA-Z0-9._() -]", "_");
    }
    
    private String guessExt(String url) {
        if (url == null) return ".mp4";
        String lower = url.toLowerCase();
        if (lower.contains(".mkv")) return ".mkv";
        if (lower.contains(".avi")) return ".avi";
        if (lower.contains(".ts")) return ".ts";
        if (lower.contains(".m3u8")) return ".mp4";
        return ".mp4";
    }
}
