package com.topent3r.multi.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.topent3r.multi.model.Episode;
import com.topent3r.multi.model.MediaItem;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * RaiPlay provider using Python script for search and download
 */
public class RaiPlayProvider implements ContentProvider {

    private static final String PYTHON_CMD = PythonHelper.getPythonCommand();
    private static final String PYTHON_SCRIPT = PythonHelper.getScriptPath("raiplay_headless.py");
    private final Gson gson = new Gson();
    private final Map<String, String> pathIdCache = new HashMap<>();  // media_id -> path_id

    @Override
    public String getDisplayName() {
        return "RaiPlay";
    }

    @Override
    public List<MediaItem> search(String query) throws Exception {
        List<String> cmd = Arrays.asList(PYTHON_CMD, PYTHON_SCRIPT, "search", query);
        String output = runPythonScript(cmd);
        
        JsonObject json = gson.fromJson(output, JsonObject.class);
        if (!"ok".equals(json.get("status").getAsString())) {
            throw new Exception("Search failed: " + json.get("message").getAsString());
        }
        
        List<MediaItem> results = new ArrayList<>();
        JsonArray arr = json.getAsJsonArray("results");
        for (JsonElement elem : arr) {
            JsonObject obj = elem.getAsJsonObject();
            String mediaId = obj.get("id").getAsString();
            String pathId = obj.has("path_id") ? obj.get("path_id").getAsString() : "";
            
            // Cache path_id for this media
            pathIdCache.put(mediaId, pathId);
            
            MediaItem item = new MediaItem(
                mediaId,
                obj.get("title").getAsString(),
                obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsString() : "tv",
                "RaiPlay",
                "RaiPlay",
                obj.has("year") && !obj.get("year").isJsonNull() ? obj.get("year").getAsString() : null
            );
            results.add(item);
        }
        
        return results;
    }

    @Override
    public List<Episode> listEpisodes(MediaItem item) throws Exception {
        // Get path_id from cache
        String pathId = pathIdCache.get(item.getId());
        if (pathId == null || pathId.isEmpty()) {
            throw new Exception("Path ID not found for media: " + item.getTitle());
        }
        
        List<String> cmd = Arrays.asList(PYTHON_CMD, PYTHON_SCRIPT, "list-episodes", pathId);
        String output = runPythonScript(cmd);
        
        JsonObject json = gson.fromJson(output, JsonObject.class);
        if (!"ok".equals(json.get("status").getAsString())) {
            throw new Exception("List episodes failed: " + json.get("message").getAsString());
        }
        
        List<Episode> episodes = new ArrayList<>();
        JsonArray arr = json.getAsJsonArray("episodes");
        
        for (JsonElement elem : arr) {
            JsonObject obj = elem.getAsJsonObject();
            Episode ep = new Episode(
                obj.get("id").getAsString(),
                obj.get("season").getAsString(),
                obj.get("episode").getAsString(),
                obj.get("title").getAsString()
            );
            episodes.add(ep);
        }
        
        return episodes;
    }

    @Override
    public void download(MediaItem item, Episode episode, Path outputDir) throws Exception {
        download(item, episode, outputDir, null);
    }
    
    @Override
    public void download(MediaItem item, Episode episode, Path outputDir, DownloadCallback callback) throws Exception {
        // Get path_id from cache
        String pathId = pathIdCache.get(item.getId());
        if (pathId == null || pathId.isEmpty()) {
            throw new Exception("Path ID not found for media: " + item.getTitle());
        }
        
        List<String> cmd;
        
        if (episode == null || episode.getSeason() == null) {
            // Download film
            cmd = Arrays.asList(PYTHON_CMD, PYTHON_SCRIPT, "download-film", pathId, outputDir.toString());
        } else {
            // Download episode
            cmd = Arrays.asList(PYTHON_CMD, PYTHON_SCRIPT, "download-episode", 
                pathId, episode.getSeason(), episode.getEpisode(), outputDir.toString());
        }
        
        if (callback != null) {
            callback.onProgress("Scaricando da RaiPlay...");
        }
        
        String output = runPythonScript(cmd);
        
        JsonObject json = gson.fromJson(output, JsonObject.class);
        if (!"ok".equals(json.get("status").getAsString())) {
            throw new Exception("Download failed: " + json.get("message").getAsString());
        }
        
        if (callback != null) {
            callback.onProgress("✅ Completato");
        }
    }
    
    private String runPythonScript(List<String> cmd) throws Exception {
        System.err.println("=== Running Python command: " + String.join(" ", cmd));
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        
        Process proc = pb.start();
        StringBuilder output = new StringBuilder();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
                // Don't add newlines - JSON is on a single line
            }
        }
        
        int exitCode = proc.waitFor();
        String result = output.toString().trim();
        
        // Remove ANSI escape sequences (from rich library)
        // Pattern: ESC [ <digits> <letter>
        result = result.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
        // Also remove other escape sequences like [3J, [H, [2J
        result = result.replaceAll("\\[[0-9]*[A-Z]", "");
        result = result.trim();
        
        System.err.println("=== Python exit code: " + exitCode);
        System.err.println("=== Python output length: " + result.length());
        System.err.println("=== Python output (first 200 chars): " + 
            (result.length() > 200 ? result.substring(0, 200) + "..." : result));
        
        if (exitCode != 0) {
            throw new Exception("Python script failed with exit code " + exitCode + ": " + result);
        }
        
        if (result.isEmpty()) {
            throw new Exception("Python script returned empty output");
        }
        
        // Validate JSON before returning
        try {
            gson.fromJson(result, JsonObject.class);
        } catch (Exception e) {
            throw new Exception("Invalid JSON from Python script: " + e.getMessage() + "\nOutput: " + result);
        }
        
        return result;
    }
}
