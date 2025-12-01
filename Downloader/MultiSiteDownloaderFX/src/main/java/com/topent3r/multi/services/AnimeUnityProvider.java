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
import java.util.List;

public class AnimeUnityProvider implements ContentProvider {
    
    private static final String PYTHON_SCRIPT = "tmp/animeunity_headless.py";
    private final Gson gson = new Gson();
    
    @Override
    public String getDisplayName() { return "AnimeUnity"; }
    
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
            if (obj.has("slug")) item.setSlug(obj.get("slug").getAsString());
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
        
        // Extract id and slug from URL: /anime/{id}-{slug}
        String[] parts = url.split("/anime/");
        if (parts.length < 2) throw new Exception("Invalid AnimeUnity URL");
        String idSlug = parts[1];
        int dashIdx = idSlug.indexOf('-');
        String animeId = dashIdx > 0 ? idSlug.substring(0, dashIdx) : item.getId();
        String slug = dashIdx > 0 ? idSlug.substring(dashIdx + 1) : (item.getSlug() != null ? item.getSlug() : "");
        
        String output = runPythonScript("list-episodes", animeId, slug);
        JsonObject json = gson.fromJson(output, JsonObject.class);
        
        if (!"ok".equals(json.get("status").getAsString())) {
            throw new Exception(json.get("message").getAsString());
        }
        
        List<Episode> allEpisodes = new ArrayList<>();
        JsonArray seasons = json.getAsJsonArray("seasons");
        for (JsonElement sEl : seasons) {
            JsonObject sObj = sEl.getAsJsonObject();
            JsonArray episodes = sObj.getAsJsonArray("episodes");
            for (JsonElement eEl : episodes) {
                JsonObject eObj = eEl.getAsJsonObject();
                Episode ep = new Episode(
                    eObj.get("id").getAsString(),
                    eObj.get("season").getAsString(),
                    eObj.get("episode").getAsString(),
                    eObj.get("title").getAsString()
                );
                if (eObj.has("index")) ep.setIndex(eObj.get("index").getAsInt());
                allEpisodes.add(ep);
            }
        }
        return allEpisodes;
    }
    
    @Override
    public void download(MediaItem item, Episode episode, Path outputDir) throws Exception {
        String url = item.getUrl();
        String[] parts = url.split("/anime/");
        if (parts.length < 2) throw new Exception("Invalid AnimeUnity URL");
        String idSlug = parts[1];
        int dashIdx = idSlug.indexOf('-');
        String animeId = dashIdx > 0 ? idSlug.substring(0, dashIdx) : item.getId();
        String slug = dashIdx > 0 ? idSlug.substring(dashIdx + 1) : (item.getSlug() != null ? item.getSlug() : "");
        
        String type = item.getType() != null ? item.getType().toLowerCase() : "";
        boolean isMovie = type.contains("movie") || type.contains("film") || episode == null;
        
        String output;
        if (isMovie) {
            output = runPythonScript("download-film", animeId, slug, outputDir.toString());
        } else {
            int index = episode.getIndex() >= 0 ? episode.getIndex() : Integer.parseInt(episode.getEpisode()) - 1;
            output = runPythonScript("download-episode", animeId, slug, String.valueOf(index), outputDir.toString());
        }
        
        output = output.replaceAll("\u001B\\[[;\\d]*m", "");
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
