package com.topent3r.multi.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.topent3r.multi.model.Episode;
import com.topent3r.multi.model.MediaItem;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CrunchyrollProvider implements ContentProvider {
    
    private static final String PYTHON_SCRIPT = PythonHelper.getScriptPath("crunchyroll_headless.py");
    private final Gson gson = new Gson();
    
    // Cache for episodes by series ID
    private final Map<String, List<Episode>> episodesCache = new HashMap<>();
    private final Map<String, List<String>> seasonsCache = new HashMap<>();
    
    @Override
    public String getDisplayName() { return "Crunchyroll"; }
    
    @Override
    public List<MediaItem> search(String query) throws Exception {
        String output = runPythonScript("search", query);
        JsonObject json = gson.fromJson(output, JsonObject.class);
        
        if (!"ok".equals(json.get("status").getAsString())) {
            throw new Exception(json.get("message").getAsString());
        }
        
        List<MediaItem> results = new ArrayList<>();
        JsonArray arr = json.getAsJsonArray("results");
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            MediaItem item = new MediaItem(
                obj.get("id").getAsString(),
                obj.get("title").getAsString(),
                obj.get("type").getAsString(),
                obj.has("year") ? obj.get("year").getAsString() : "",
                obj.get("source").getAsString(),
                obj.get("sourceAlias").getAsString()
            );
            item.setUrl(obj.get("url").getAsString());
            results.add(item);
        }
        return results;
    }
    
    @Override
    public List<Episode> listEpisodes(MediaItem item) throws Exception {
        String url = item.getUrl();
        if (url == null || url.isEmpty()) {
            throw new Exception("URL mancante per " + item.getTitle());
        }
        
        // Extract series ID from URL (format: https://www.crunchyroll.com/series/XXXXX)
        String seriesId = url.substring(url.lastIndexOf('/') + 1);
        
        // Check cache
        if (episodesCache.containsKey(seriesId)) {
            return episodesCache.get(seriesId);
        }
        
        String output = runPythonScript("list-episodes", seriesId);
        JsonObject json = gson.fromJson(output, JsonObject.class);
        
        if (!"ok".equals(json.get("status").getAsString())) {
            throw new Exception(json.get("message").getAsString());
        }
        
        List<Episode> allEpisodes = new ArrayList<>();
        List<String> seasonList = new ArrayList<>();
        
        JsonArray seasons = json.getAsJsonArray("seasons");
        for (JsonElement sEl : seasons) {
            JsonObject sObj = sEl.getAsJsonObject();
            String seasonNum = sObj.get("season").getAsString();
            seasonList.add(seasonNum);
            
            JsonArray episodes = sObj.getAsJsonArray("episodes");
            for (JsonElement eEl : episodes) {
                JsonObject eObj = eEl.getAsJsonObject();
                Episode ep = new Episode(
                    eObj.get("id").getAsString(),
                    eObj.get("season").getAsString(),
                    eObj.get("episode").getAsString(),
                    eObj.get("title").getAsString()
                );
                allEpisodes.add(ep);
            }
        }
        
        episodesCache.put(seriesId, allEpisodes);
        seasonsCache.put(seriesId, seasonList);
        
        return allEpisodes;
    }
    
    public List<String> getSeasons(MediaItem item) throws Exception {
        String url = item.getUrl();
        String seriesId = url.substring(url.lastIndexOf('/') + 1);
        if (!seasonsCache.containsKey(seriesId)) {
            listEpisodes(item);
        }
        return seasonsCache.getOrDefault(seriesId, new ArrayList<>());
    }
    
    @Override
    public void download(MediaItem item, Episode episode, Path outputDir) throws Exception {
        downloadInternal(item, episode, outputDir);
    }
    
    private void downloadInternal(MediaItem item, Episode episode, Path outputDir) throws Exception {
        String url = item.getUrl();
        if (url == null || url.isEmpty()) {
            throw new Exception("URL mancante");
        }
        
        String seriesId = url.substring(url.lastIndexOf('/') + 1);
        String type = item.getType() == null ? "" : item.getType().toLowerCase();
        boolean isMovie = type.contains("film") || type.contains("movie");
        
        String output;
        if (isMovie) {
            output = runPythonScript("download-film", url, item.getTitle(), outputDir.toString());
        } else {
            if (episode == null) {
                throw new Exception("Episodio non specificato");
            }
            output = runPythonScript("download-episode", seriesId, episode.getSeason(), episode.getEpisode(), outputDir.toString());
        }
        
        // Strip ANSI codes
        output = output.replaceAll("\u001B\\[[;\\d]*m", "").replaceAll("\\[\\d+[A-Za-z]", "");
        
        // Find JSON
        int jsonStart = output.indexOf('{');
        int jsonEnd = output.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            output = output.substring(jsonStart, jsonEnd + 1);
        }
        
        JsonObject json = gson.fromJson(output, JsonObject.class);
        if (!"ok".equals(json.get("status").getAsString())) {
            throw new Exception(json.get("message").getAsString());
        }
    }
    
    private String runPythonScript(String... args) throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent().getParent();
        Path scriptPath = projectRoot.resolve(PYTHON_SCRIPT);
        
        List<String> cmd = new ArrayList<>();
        cmd.add("python3");
        cmd.add(scriptPath.toString());
        for (String arg : args) {
            cmd.add(arg);
        }
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectRoot.toFile());
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("TERM", "dumb");
        pb.redirectErrorStream(true);
        
        Process proc = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        
        int exitCode = proc.waitFor();
        String output = sb.toString().trim();
        
        if (exitCode != 0 && !output.contains("\"status\"")) {
            throw new Exception("Python script failed: " + output);
        }
        
        return output;
    }
}
