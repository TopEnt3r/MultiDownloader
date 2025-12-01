package com.topent3r.multi.services;

import com.topent3r.multi.model.Episode;
import com.topent3r.multi.model.MediaItem;
import com.topent3r.multi.m3u.services.HttpDownloader;
import okhttp3.OkHttpClient;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Scheletro di provider Java per StreamingCommunity.
 * Qui andrà portata la logica Python (ricerca, metadata, download).
 */
public class StreamingCommunityProvider implements ContentProvider {

    private final InMemoryCookieJar cookieStore = new InMemoryCookieJar();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .followRedirects(true)
            .cookieJar(cookieStore)
            .build();
    private volatile String lastPyTrace;
    
    private String getBaseUrl() {
        try {
            com.topent3r.multi.m3u.utils.SettingsManager sm = new com.topent3r.multi.m3u.utils.SettingsManager();
            return sm.load().urlStreamingCommunity;
        } catch (Exception e) {
            return "https://streamingcommunity.computer";
        }
    }

    private static String ua() {
        String[] uas = new String[] {
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127 Safari/537.36"
        };
        return uas[ThreadLocalRandom.current().nextInt(uas.length)];
    }

    // --- ContentProvider API ---
    @Override
    public String getDisplayName() { return "StreamingCommunity"; }

    @Override
    public java.util.List<MediaItem> search(String query) throws Exception {
        String base = getBaseUrl();
        java.util.List<MediaItem> res = new java.util.ArrayList<>();
        java.util.Map<String, MediaItem> uniq = new java.util.LinkedHashMap<>();
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
        // Primary: use Inertia JSON with correct version header
        String version = getInertiaVersion(base);
        if (version != null) {
            JsonObject page = fetchInertiaJson(base + "/it/search?q=" + q, version);
            if (page != null) {
                addTitlesFromInertia(page, uniq);
                scanTitlesForItems(page, uniq);
            }
        }
        // Fallbacks: try without inertia on various endpoints
        if (uniq.isEmpty()) {
            String[] candidates = new String[] {
                    base + "/it/search?q=" + q,
                    base + "/it/search?search=" + q,
                    base + "/it/titles?search=" + q,
                    base + "/it?search=" + q
            };
            for (String url : candidates) {
                JsonObject page = fetchAppDataPage(url);
                if (page != null) {
                    addTitlesFromInertia(page, uniq);
                    scanTitlesForItems(page, uniq);
                }
                if (!uniq.isEmpty()) break;
            }
        }
        // Fallback: parse direct id-slug or full URL pasted in the search box
        if (uniq.isEmpty() && query != null) {
            String trimmed = query.trim();
            java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("^(\\d+)-([A-Za-z0-9-]+)$").matcher(trimmed);
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("/it/titles/(\\d+)-([A-Za-z0-9-]+)").matcher(trimmed);
            java.util.regex.Matcher m3 = java.util.regex.Pattern.compile("/it/watch/(\\d+)\\?e=(\\d+)").matcher(trimmed);
            String idSlug = null;
            if (m1.find()) idSlug = m1.group(1) + "-" + m1.group(2);
            else if (m2.find()) idSlug = m2.group(1) + "-" + m2.group(2);
            else if (m3.find()) { // have mid only, try to resolve slug from titles page
                String mid = m3.group(1);
                JsonObject tp = fetchAppDataPage(base + "/it/titles/" + mid);
                if (tp != null) {
                    java.util.Map<String, MediaItem> tmp = new java.util.LinkedHashMap<>();
                    scanTitlesForItems(tp, tmp);
                    for (String k : tmp.keySet()) { if (k.startsWith(mid + "-")) { idSlug = k; break; } }
                }
            }
            if (idSlug != null) {
                uniq.putIfAbsent(idSlug, new MediaItem(idSlug, idSlug, "Serie", "StreamingCommunity", "SC", null));
            }
        }
        // Python fallback: replicate search via curl_cffi if still empty
        if (uniq.isEmpty()) {
            java.util.List<MediaItem> py = pythonSearch(base, query);
            for (MediaItem mi : py) {
                if (mi != null && mi.getId() != null) {
                    uniq.putIfAbsent(mi.getId(), mi);
                }
            }
        }
        res.addAll(uniq.values());
        return res;
    }

    @Override
    public java.util.List<Episode> listEpisodes(MediaItem item) throws Exception {
        String base = getBaseUrl();
        String idSlug = item == null ? null : item.getId();
        if (idSlug == null || !idSlug.matches("\\d+-.*")) return java.util.Collections.emptyList();
        java.util.List<Episode> out = new java.util.ArrayList<>();

        // Get title page to retrieve seasons and inertia version
        JsonObject titlePage = fetchAppDataPage(base + "/it/titles/" + idSlug);
        String version = null;
        java.util.List<Integer> seasons = new java.util.ArrayList<>();
        if (titlePage != null) {
            version = getAsString(titlePage, "version");
            JsonObject props = titlePage.has("props") && titlePage.get("props").isJsonObject() ? titlePage.getAsJsonObject("props") : null;
            if (props != null && props.has("title") && props.get("title").isJsonObject()) {
                JsonObject t = props.getAsJsonObject("title");
                if (t.has("seasons") && t.get("seasons").isJsonArray()) {
                    JsonArray sa = t.getAsJsonArray("seasons");
                    for (JsonElement se : sa) {
                        if (se.isJsonObject()) {
                            Integer n = parseIntSafe(getAsString(se.getAsJsonObject(), "number"));
                            if (n != null && n > 0) seasons.add(n);
                        }
                    }
                }
            }
        }
        if (seasons.isEmpty()) {
            // fallback to first 12 seasons
            for (int i=1;i<=12;i++) seasons.add(i);
        }

        boolean any = false;
        for (Integer s : seasons) {
            if (s == null || s <= 0) continue;
            JsonObject page = version != null ? fetchInertiaJson(base + "/it/titles/" + idSlug + "/season-" + s, version)
                                              : fetchAppDataPage(base + "/it/titles/" + idSlug + "/season-" + s);
            if (page == null) {
                if (any) continue;
                else continue;
            }
            JsonArray episodes = null;
            // Prefer Inertia path like Python: props.loadedSeason.episodes
            try {
                JsonObject props = page.has("props") && page.get("props").isJsonObject() ? page.getAsJsonObject("props") : null;
                JsonObject ls = props != null && props.has("loadedSeason") && props.get("loadedSeason").isJsonObject() ? props.getAsJsonObject("loadedSeason") : null;
                if (ls != null && ls.has("episodes") && ls.get("episodes").isJsonArray()) {
                    episodes = ls.getAsJsonArray("episodes");
                }
            } catch (Exception ignore) {}
            if (episodes == null) episodes = findEpisodesArray(page);
            if (episodes == null || episodes.size() == 0) { any = true; continue; }

            for (JsonElement el : episodes) {
                if (!el.isJsonObject()) continue;
                JsonObject eo = el.getAsJsonObject();
                String eid = firstNonNull(
                        getAsString(eo, "id"),
                        getAsString(eo, "episode_id"),
                        getAsString(eo, "video_id"),
                        getAsString(eo, "stream_id")
                );
                String en = firstNonNull(getAsString(eo, "number"), getAsString(eo, "episode_number"), getAsString(eo, "episode"));
                String et = firstNonNull(getAsString(eo, "title"), getAsString(eo, "name"));
                if ((eid == null || eid.isBlank())) {
                    String eurl = getAsString(eo, "url");
                    if (eurl != null) {
                        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\d+)(?:$|[^0-9])").matcher(eurl);
                        String last = null; while (mm.find()) { last = mm.group(1); }
                        if (last != null) eid = last;
                    }
                }
                if (en == null || en.isBlank()) {
                    System.err.println("=== Skipping episode with missing number: eid=" + eid + ", season=" + s + ", title=" + et);
                    continue;
                }
                if (et == null) et = "";
                Episode ep = new Episode(eid, String.valueOf(s), en, et);
                System.err.println("=== Adding episode: " + ep + " (season=" + s + ", number=" + en + ", id=" + eid + ")");
                out.add(ep);
            }
            any = true;
        }
        // Python fallback if we found too few episodes
        if (out.size() <= 1) {
            java.util.List<Episode> py = pythonListEpisodes(base, idSlug);
            if (!py.isEmpty()) return py;
        }
        return out;
    }

    @Override
    public void download(MediaItem item, Episode episode, Path outputDir) throws Exception {
        download(item, episode, outputDir, null);
    }

    @Override
    public void download(MediaItem item, Episode episode, Path outputDir, DownloadCallback callback) throws Exception {
        if (item == null) throw new IllegalArgumentException("item null");
        if (episode == null) throw new IllegalArgumentException("Devi selezionare almeno un episodio");
        
        String id = item.getId();
        String slug = null; String numericId = null;
        if (id != null && id.contains("-")) { int i=id.indexOf('-'); numericId = id.substring(0,i); slug = id.substring(i+1); }
        if (numericId == null) throw new IllegalStateException("ID media non valido");
        
        String type = item.getType() == null ? "" : item.getType().toLowerCase();
        boolean isMovie = type.contains("film") || type.contains("movie");
        
        System.err.println("=== Content type: " + type + ", isMovie: " + isMovie);
        System.err.println("=== Episode debug: season='" + episode.getSeason() + "', episode='" + episode.getEpisode() + "', title='" + episode.getTitle() + "', id='" + episode.getId() + "'");
        
        java.nio.file.Files.createDirectories(outputDir);
        
        // Use standardized filename format
        String fileName;
        if (isMovie) {
            fileName = com.topent3r.multi.utils.FileNameFormatter.formatMovie(item.getTitle(), item.getYear());
        } else {
            fileName = com.topent3r.multi.utils.FileNameFormatter.formatEpisode(item.getTitle(), episode.getSeason(), episode.getEpisode(), episode.getTitle());
        }
        
        Path out = outputDir.resolve(fileName);
        String base = getBaseUrl();

        boolean ok;
        if (isMovie) {
            // For movies, use direct download without season/episode
            System.err.println("=== Downloading MOVIE: " + item.getTitle());
            ok = pythonDownloadMovie(base, numericId, slug != null ? slug : "", out.toString(), callback);
        } else {
            // For TV series, use season/episode
            Integer sNum = parseIntSafe(episode.getSeason());
            Integer eNum = parseIntSafe(episode.getEpisode());
            
            if (sNum == null || eNum == null) {
                String msg = "Episodio mancante o non valido - Season: '" + episode.getSeason() + "' → " + sNum + ", Episode: '" + episode.getEpisode() + "' → " + eNum;
                System.err.println("=== " + msg);
                throw new IllegalArgumentException(msg);
            }
            
            System.err.println("=== Downloading TV EPISODE: " + item.getTitle() + " S" + sNum + "E" + eNum);
            ok = pythonDownloadBySE(base, numericId, slug != null ? slug : "", sNum, eNum, out.toString(), callback);
        }
        
        if (!ok) {
            String trace = (lastPyTrace == null || lastPyTrace.isBlank()) ? "py-se" : lastPyTrace;
            // Se il file esiste dopo il download fallito, significa che FFmpeg ha fatto il fallback con successo
            if (java.nio.file.Files.exists(out)) {
                System.err.println("=== Download completato via FFmpeg fallback");
                return; // Success via FFmpeg
            }
            throw new IllegalStateException("Sorgente video non trovata (" + trace + ")");
        }
    }

    private java.util.List<String> listSeasonEParams(String base, String idSlug, String seasonStr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            int sNum = parseIntSafe(seasonStr) == null ? 1 : parseIntSafe(seasonStr);
            JsonObject page = fetchAppDataPage(base + "/it/titles/" + idSlug + "/season-" + sNum);
            if (page == null) return out;
            JsonArray episodes = findEpisodesArray(page);
            if (episodes == null) return out;
            for (JsonElement el : episodes) {
                if (!el.isJsonObject()) continue;
                JsonObject eo = el.getAsJsonObject();
                String eurl = getAsString(eo, "url");
                if (eurl != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]e=(\\d+)").matcher(eurl);
                    if (m.find()) out.add(m.group(1));
                }
            }
        } catch (Exception ignore) {}
        return out;
    }

    // Tenta a trovare il media ID corretto per la pagina watch cercando nelle URL degli episodi
    private String findWatchMid(String base, String idSlug, String episodeId) {
        try {
            for (int s = 1; s <= 6; s++) {
                JsonObject page = fetchAppDataPage(base + "/it/titles/" + idSlug + "/season-" + s);
                if (page == null) continue;
                JsonArray episodes = findEpisodesArray(page);
                if (episodes == null) continue;
                for (JsonElement el : episodes) {
                    if (!el.isJsonObject()) continue;
                    JsonObject eo = el.getAsJsonObject();
                    String eid = getAsString(eo, "id");
                    if (episodeId != null && episodeId.equals(eid)) {
                        String eurl = getAsString(eo, "url");
                        if (eurl != null) {
                            java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("/it/watch/(\\d+)\\?e=(\\d+)").matcher(eurl);
                            if (m1.find()) return m1.group(1);
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private java.util.List<Episode> pythonListEpisodes(String base, String idSlug) {
        java.util.List<Episode> out = new java.util.ArrayList<>();
        String repo = "/Users/andreacassan/AndroidStudioProjects/evolutix-panel 2/Downloader/StreamingCommunity/StreamingCommunity-main";
        try {
            if (idSlug == null || !idSlug.contains("-")) return out;
            String mid = idSlug.substring(0, idSlug.indexOf('-'));
            String slug = idSlug.substring(idSlug.indexOf('-') + 1);
            java.nio.file.Path p = java.nio.file.Paths.get(repo);
            if (!java.nio.file.Files.exists(p)) return out;
            String code = String.join("\n",
                    "import sys, os, json, types, importlib.util",
                    "root=os.path.abspath(sys.argv[1])",
                    "os.chdir(root)",
                    "pkg=os.path.join(root, 'StreamingCommunity')",
                    "sys.path.insert(0, root); sys.path.insert(0, pkg)",
                    "m=types.ModuleType('StreamingCommunity'); m.__path__=[pkg]; sys.modules['StreamingCommunity']=m",
                    "import StreamingCommunity.Util.http_client as http_client",
                    "from StreamingCommunity.Util.headers import get_userAgent",
                    "# Force curl client",
                    "http_client.create_client = lambda **k: http_client.create_client_curl(**k)",
                    "scrape_path=os.path.join(pkg,'Api','Site','streamingcommunity','util','ScrapeSerie.py')",
                    "spec=importlib.util.spec_from_file_location('ScrapeSerie', scrape_path)",
                    "ScrapeSerie=importlib.util.module_from_spec(spec); spec.loader.exec_module(ScrapeSerie)",
                    "GetSerieInfo=ScrapeSerie.GetSerieInfo",
                    "base=sys.argv[2]",
                    "mid=int(sys.argv[3])",
                    "slug=sys.argv[4]",
                    "gs=GetSerieInfo(base+'/it', mid, slug)",
                    "n=gs.getNumberSeason()",
                    "res=[]",
                    "for s in range(1, n+1):",
                    "  eps=gs.getEpisodeSeasons(s) or []",
                    "  for e in eps:",
                    "    try:",
                    "      eid = e.get('id') or e.get('episode_id') or e.get('video_id') or e.get('stream_id')",
                    "      en = e.get('number') or e.get('episode_number') or e.get('episode')",
                    "      et = e.get('title') or e.get('name') or ''",
                    "      if en is not None:",
                    "        res.append({'id': str(eid) if eid is not None else None, 's': s, 'e': str(en), 't': et})",
                    "    except Exception:",
                    "      pass",
                    "print(json.dumps(res))"
            );
            ProcessBuilder pb = new ProcessBuilder(pythonExe(repo), "-c", code, repo, base, mid, slug);
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line; while ((line = br.readLine()) != null) sb.append(line);
                pr.waitFor();
                String txt = sb.toString().trim();
                if (txt.isEmpty()) return out;
                com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(txt).getAsJsonArray();
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String eid = getAsString(o, "id");
                    String es = String.valueOf(o.get("s").getAsInt());
                    String ee = getAsString(o, "e");
                    String tt = getAsString(o, "t");
                    out.add(new Episode(eid, es, ee, tt==null?"":tt));
                }
            }
        } catch (Exception ignore) {}
        return out;
    }

    // Trova il valore e= corretto per la watch page per stagione/episodio
    private String findWatchE(String base, String idSlug, String seasonStr, String episodeStr) {
        try {
            int sNum = parseIntSafe(seasonStr) == null ? 1 : parseIntSafe(seasonStr);
            int eNum = parseIntSafe(episodeStr) == null ? 1 : parseIntSafe(episodeStr);
            JsonObject page = fetchAppDataPage(base + "/it/titles/" + idSlug + "/season-" + sNum);
            if (page == null) return null;
            JsonArray episodes = findEpisodesArray(page);
            if (episodes == null) return null;
            for (JsonElement el : episodes) {
                if (!el.isJsonObject()) continue;
                JsonObject eo = el.getAsJsonObject();
                String num = firstNonNull(getAsString(eo, "number"), getAsString(eo, "episode_number"), getAsString(eo, "episode"));
                Integer numI = parseIntSafe(num);
                if (numI != null && numI == eNum) {
                    String eurl = getAsString(eo, "url");
                    if (eurl != null) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]e=(\\d+)").matcher(eurl);
                        if (m.find()) return m.group(1);
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private boolean pythonDownloadMovie(String base, String numericId, String slug, String outputPath, DownloadCallback callback) {
        String repo = "/Users/andreacassan/AndroidStudioProjects/evolutix-panel 2/Downloader/StreamingCommunity/StreamingCommunity-main";
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(repo);
            if (!java.nio.file.Files.exists(p)) return false;
            
            // Simplified Python script for movies - no episode selection needed
            String code = String.join("\n",
                    "import sys, os, types, importlib.util, importlib, json",
                    "root=os.path.abspath(sys.argv[1])",
                    "os.chdir(root)",
                    "pkg=os.path.join(root, 'StreamingCommunity')",
                    "sys.path.insert(0, root)",
                    "sys.path.insert(0, pkg)",
                    "m=types.ModuleType('StreamingCommunity'); m.__path__=[pkg]; sys.modules['StreamingCommunity']=m",
                    "from bs4 import BeautifulSoup",
                    "from StreamingCommunity.Util.headers import get_userAgent",
                    "import StreamingCommunity.Util.http_client as http_client",
                    "http_client.create_client = lambda **k: http_client.create_client_curl(**k)",
                    "# Import vixcloud",
                    "vix=importlib.import_module('StreamingCommunity.Api.Player.vixcloud')",
                    "vix.create_client = http_client.create_client_curl",
                    "VideoSource = vix.VideoSource",
                    "base=sys.argv[2]",
                    "mid=int(sys.argv[3])",
                    "dst=sys.argv[4]",
                    "t=[]",
                    "t.append(f'py-movie:start(mid={mid})')",
                    "watch_ref=base+'/it/watch/'+str(mid)",
                    "vs=VideoSource(base+'/it', True, mid)",
                    "try:",
                    "  t.append('iframe:request'); vs.get_iframe(mid); vs.get_content(); t.append('iframe:OK')",
                    "except Exception as e:",
                    "  t.append(f'iframe:ERR:{e}')",
                    "t.append('py-pl:start')",
                    "try:",
                    "  pl=vs.get_playlist()",
                    "  if pl:",
                    "    t.append(f'py-pl:get=OK(len={len(pl)})')",
                    "  else:",
                    "    t.append('py-pl:get=None')",
                    "except Exception as e:",
                    "  t.append(f'py-pl:get-ERR:{e}')",
                    "  pl=None",
                    "out={'ok': pl is not None and len(pl)>0, 'pl': pl, 'trace': '->'.join(t), 'watch': watch_ref}",
                    "print(json.dumps(out), flush=True)",
                    "sys.exit(0)"
            );
            
            String pyExe = pythonExe(repo);
            if (pyExe == null) return false;
            
            java.io.File tmpScript = java.io.File.createTempFile("sc_movie_", ".py");
            tmpScript.deleteOnExit();
            java.nio.file.Files.writeString(tmpScript.toPath(), code, java.nio.charset.StandardCharsets.UTF_8);
            
            ProcessBuilder pb = new ProcessBuilder(pyExe, tmpScript.getAbsolutePath(), repo, base, numericId, outputPath);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            
            String lastLine = null;
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lastLine = line;
                    System.err.println("[PY-MOVIE] " + line);
                }
            }
            
            proc.waitFor();
            return handlePythonOutput(lastLine, outputPath, callback);
            
        } catch (Exception e) {
            System.err.println("=== Python movie download error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean handlePythonOutput(String lastJson, String outputPath, DownloadCallback callback) {
        if (lastJson == null || lastJson.isBlank()) {
            System.err.println("=== No JSON output from Python");
            return false;
        }
        
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(lastJson).getAsJsonObject();
            boolean ok = obj.has("ok") && obj.get("ok").getAsBoolean();
            String trace = obj.has("trace") && !obj.get("trace").isJsonNull() ? obj.get("trace").getAsString() : null;
            String playlist = obj.has("pl") && !obj.get("pl").isJsonNull() ? obj.get("pl").getAsString() : null;
            String watchRef = obj.has("watch") && !obj.get("watch").isJsonNull() ? obj.get("watch").getAsString() : null;
            
            lastPyTrace = trace;
            System.err.println("=== lastJson: " + lastJson);
            System.err.println("=== trace: " + trace);
            System.err.println("=== playlist: " + playlist);
            
            // Se abbiamo una playlist, scarica con il downloader Python nativo (8 workers come app originale)
            if (playlist != null && !playlist.isBlank()) {
                System.err.println("=== Using Python native HLS downloader (8 workers)...");
                return pythonNativeDownload(playlist, outputPath, watchRef, callback);
            }
            
            return ok;
        } catch (Exception e) {
            System.err.println("=== Failed to parse Python output: " + e.getMessage());
            return false;
        }
    }

    private boolean pythonNativeDownload(String playlist, String outputPath, String watchRef, DownloadCallback callback) {
        String repo = "/Users/andreacassan/AndroidStudioProjects/evolutix-panel 2/Downloader/StreamingCommunity/StreamingCommunity-main";
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(repo);
            if (!java.nio.file.Files.exists(p)) return false;
            
            // Usa ffmpeg con progress e multi-thread
            String code = String.join("\n",
                    "import sys, os, subprocess, re, time",
                    "pl_url=sys.argv[1]",
                    "out_path=sys.argv[2]",
                    "print(f'[PY-HLS] Downloading with ffmpeg (multi-thread)...', flush=True)",
                    "print(f'[PY-HLS] Playlist: {pl_url}', flush=True)",
                    "print(f'[PY-HLS] Output: {out_path}', flush=True)",
                    "os.makedirs(os.path.dirname(out_path), exist_ok=True)",
                    "cmd = [",
                    "    '/opt/homebrew/bin/ffmpeg', '-y',",
                    "    '-threads', '8',",
                    "    '-i', pl_url,",
                    "    '-c', 'copy',",
                    "    '-bsf:a', 'aac_adtstoasc',",
                    "    '-progress', 'pipe:1',",
                    "    '-stats_period', '1',",
                    "    out_path",
                    "]",
                    "proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)",
                    "start_time = time.time()",
                    "last_size = 0",
                    "while True:",
                    "    line = proc.stdout.readline()",
                    "    if not line and proc.poll() is not None:",
                    "        break",
                    "    if 'out_time_ms=' in line:",
                    "        try:",
                    "            ms = int(line.split('=')[1].strip())",
                    "            secs = ms / 1000000",
                    "            mins = int(secs // 60)",
                    "            s = int(secs % 60)",
                    "            if os.path.exists(out_path):",
                    "                cur_size = os.path.getsize(out_path)",
                    "                elapsed = time.time() - start_time",
                    "                if elapsed > 0:",
                    "                    speed_mb = (cur_size / 1024 / 1024) / elapsed",
                    "                    print(f'[PROGRESS] {mins:02d}:{s:02d} - {cur_size/1024/1024:.1f} MB - {speed_mb:.2f} MB/s', flush=True)",
                    "        except: pass",
                    "proc.wait()",
                    "if proc.returncode == 0 and os.path.exists(out_path):",
                    "    size = os.path.getsize(out_path)",
                    "    elapsed = time.time() - start_time",
                    "    avg_speed = (size / 1024 / 1024) / elapsed if elapsed > 0 else 0",
                    "    print(f'[PY-HLS] Completed! {size/1024/1024:.1f} MB in {elapsed:.0f}s ({avg_speed:.2f} MB/s)', flush=True)",
                    "    sys.exit(0)",
                    "else:",
                    "    err = proc.stderr.read()",
                    "    print(f'[PY-HLS] ffmpeg error: {err}', flush=True)",
                    "    sys.exit(1)"
            );
            
            String pyExe = pythonExe(repo);
            if (pyExe == null) return false;
            
            java.io.File tmpScript = java.io.File.createTempFile("sc_hls_", ".py");
            tmpScript.deleteOnExit();
            java.nio.file.Files.writeString(tmpScript.toPath(), code, java.nio.charset.StandardCharsets.UTF_8);
            
            ProcessBuilder pb = new ProcessBuilder(pyExe, tmpScript.getAbsolutePath(), playlist, outputPath);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.err.println("[PY-HLS] " + line);
                    
                    // Parse [PROGRESS] lines: [PROGRESS] 00:45 - 123.4 MB - 5.67 MB/s
                    if (line.startsWith("[PROGRESS]")) {
                        String progress = line.substring(11).trim(); // Remove "[PROGRESS] "
                        if (callback != null) callback.onProgress(progress);
                        // Extract speed
                        if (line.contains("MB/s")) {
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9.]+)\\s*MB/s").matcher(line);
                            if (m.find()) {
                                if (callback != null) callback.onSpeed(m.group(1) + " MB/s");
                            }
                        }
                    }
                }
            }
            
            int exitCode = proc.waitFor();
            if (exitCode == 0 && java.nio.file.Files.exists(java.nio.file.Paths.get(outputPath))) {
                long size = java.nio.file.Files.size(java.nio.file.Paths.get(outputPath));
                System.err.println("=== Python HLS download SUCCESS, size=" + size);
                if (callback != null) callback.onProgress("✅ " + formatSize(size));
                return true;
            }
            
            System.err.println("=== Python HLS download FAILED, exit=" + exitCode);
            return false;
            
        } catch (Exception e) {
            System.err.println("=== Python HLS download error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean pythonDownloadBySE(String base, String numericId, String slug, Integer season, Integer episode, String outputPath, DownloadCallback callback) {
        String repo = "/Users/andreacassan/AndroidStudioProjects/evolutix-panel 2/Downloader/StreamingCommunity/StreamingCommunity-main";
        try {
            if (season == null || episode == null) return false;
            java.nio.file.Path p = java.nio.file.Paths.get(repo);
            if (!java.nio.file.Files.exists(p)) return false;
            String code = String.join("\n",
                    "import sys, os, types, importlib.util, importlib, json",
                    "root=os.path.abspath(sys.argv[1])",
                    "pkg=os.path.join(root, 'StreamingCommunity')",
                    "sys.path.insert(0, root)",
                    "sys.path.insert(0, pkg)",
                    "m=types.ModuleType('StreamingCommunity'); m.__path__=[pkg]; sys.modules['StreamingCommunity']=m",
                    "from bs4 import BeautifulSoup",
                    "from StreamingCommunity.Util.headers import get_userAgent",
                    "import StreamingCommunity.Util.http_client as http_client",
                    "# Force curl client BEFORE importing site modules",
                    "http_client.create_client = lambda **k: http_client.create_client_curl(**k)",
                    "# Stub optional telegram deps to avoid import errors",
                    "import types as _types, sys as _sys",
                    "_sys.modules.setdefault('telebot', _types.ModuleType('telebot'))",
                    "tg_stub = _types.ModuleType('StreamingCommunity.TelegramHelp.telegram_bot')",
                    "tg_stub.get_bot_instance = lambda: None",
                    "class _TgSession: pass",
                    "tg_stub.TelegramSession = _TgSession",
                    "_sys.modules['StreamingCommunity.TelegramHelp.telegram_bot'] = tg_stub",
                    "# Import ScrapeSerie with patched client - use spec to avoid __init__ side effects",
                    "scrape_path=os.path.join(pkg,'Api','Site','streamingcommunity','util','ScrapeSerie.py')",
                    "spec=importlib.util.spec_from_file_location('ScrapeSerie', scrape_path)",
                    "ScrapeSerie=importlib.util.module_from_spec(spec); spec.loader.exec_module(ScrapeSerie)",
                    "GetSerieInfo=ScrapeSerie.GetSerieInfo",
                    "# Preload Lib packages to avoid executing __init__ that may import TOR/qbittorrent",
                    "lib_dir=os.path.join(pkg,'Lib'); dwn_dir=os.path.join(lib_dir,'Downloader'); hls_dir=os.path.join(dwn_dir,'HLS')",
                    "for name, path in [('StreamingCommunity.Lib', lib_dir), ('StreamingCommunity.Lib.Downloader', dwn_dir), ('StreamingCommunity.Lib.Downloader.HLS', hls_dir)]:",
                    "    if name not in _sys.modules:",
                    "        mod=_types.ModuleType(name); mod.__path__=[path]; _sys.modules[name]=mod",
                    "# Load HLS downloader via spec to avoid __init__ side-effects",
                    "spec2=importlib.util.spec_from_file_location('StreamingCommunity.Lib.Downloader.HLS.downloader', os.path.join(hls_dir,'downloader.py'))",
                    "hls_mod=importlib.util.module_from_spec(spec2); spec2.loader.exec_module(hls_mod)",
                    "HLS_Downloader=hls_mod.HLS_Downloader",
                    "# Import vixcloud AFTER patching client",
                    "vix=importlib.import_module('StreamingCommunity.Api.Player.vixcloud')",
                    "# Ensure vixcloud uses curl client",
                    "vix.create_client = http_client.create_client_curl",
                    "VideoSource = vix.VideoSource",
                    "base=sys.argv[2]",
                    "mid=int(sys.argv[3])",
                    "slug=sys.argv[4]",
                    "sn=int(sys.argv[5])",
                    "en=int(sys.argv[6])",
                    "dst=sys.argv[7]",
                    "t=[]",
                    "t.append(f'py-se:start(mid={mid},slug={slug},s={sn},e={en})')",
                    "# client already patched above",
                    "gs=GetSerieInfo(base+'/it', mid, slug)",
                    "gs.getNumberSeason()",
                    "ep=gs.selectEpisode(sn, en-1)",
                    "def _eid(x):",
                    "  try:\n    return x['id']\n  except Exception:\n    pass",
                    "  for k in ('id','episode_id','video_id','stream_id'):",
                    "    v=getattr(x,k,None)",
                    "    if v is not None: return v",
                    "  return None",
                    "eid=_eid(ep)",
                    "t.append(f'eid={eid}')",
                    "watch_ref=base+'/it/watch/'+str(mid)+'?e='+str(eid)",
                    "# client already patched above",
                    "vs=VideoSource(base+'/it', True, mid)",
                    "script=None; resp=None; sess=None",
                    "try:",
                    "  t.append('iframe:request'); vs.get_iframe(eid); vs.get_content(); script='ok'; t.append('iframe:OK')",
                    "except Exception as iframe_err:",
                    "  t.append(f'iframe:ERR:{iframe_err}')",
                    "if script is None:",
                    "    t.append('embed:start')",
                    "    sess=http_client.create_client_curl(headers={'User-Agent': get_userAgent(), 'Accept-Language':'it-IT,it;q=0.9,en;q=0.8'}, allow_redirects=True)",
                    "    t.append('embed-url'); embed=sess.get(base+'/it/embed-url/'+str(eid)).text.strip()",
                    "    t.append(f'embed={embed[:50]}')",
                    "    sess.headers.update({'Referer': watch_ref, 'Origin': base})",
                    "    resp=sess.get(embed)",
                    "    t.append(f'embed-resp:status={resp.status_code}')",
                    "    soup=BeautifulSoup(resp.text, 'html.parser')",
                    "    scr=None; script_count=0",
                    "    for tag in soup.find_all('script'):",
                    "        script_count+=1",
                    "        try:",
                    "            txt=tag.text or ''",
                    "            if 'masterPlaylist' in txt or 'video' in txt:",
                    "                scr=txt; t.append(f'script:found(len={len(scr)})'); break",
                    "        except Exception:",
                    "            pass",
                    "    t.append(f'scripts:total={script_count},found={scr is not None}')",
                    "    if scr:",
                    "        t.append('script:parse'); vs.parse_script(scr)",
                    "    else:",
                    "        t.append('script:NOT-FOUND')",
                    "t.append('py-pl:start')",
                    "try:",
                    "  pl=vs.get_playlist()",
                    "  if pl:",
                    "    t.append(f'py-pl:get=OK(len={len(pl)})')",
                    "  else:",
                    "    t.append('py-pl:get=None')",
                    "except Exception as e:",
                    "  t.append(f'py-pl:get-ERR:{e}')",
                    "  pl=None",
                    "if not pl:",
                    "    t.append('py-pl:fallback-regex')",
                    "    txt=resp.text if resp is not None else ''",
                    "    import re",
                    "    m=re.search(r'https?://[^\\s\\x22\\x27<>]+\\.m3u8[^\\s\\x22\\x27<>]*', txt)",
                    "    pl=m.group(0) if m else None",
                    "    if pl is None: t.append('py-pl:ERR-no-m3u8')",
                    "    else: t.append('py-pl:OK-regex')",
                    "if not pl and sess is not None:",
                    "    try:",
                    "        wr=sess.get(watch_ref)",
                    "        txt2=wr.text",
                    "        import re as _re",
                    "        m2=_re.search(r'https?://[^\\s\\x22\\x27<>]+\\.m3u8[^\\s\\x22\\x27<>]*', txt2)",
                    "        pl=m2.group(0) if m2 else None",
                    "        t.append('watch:request(mid='+str(mid)+',e='+str(eid)+')')",
                    "    except Exception:",
                    "        pass",
                    "hdr={'User-Agent': get_userAgent(), 'Referer': watch_ref, 'Origin': base}",
                    "res=HLS_Downloader(m3u8_url=pl, output_path=dst, headers=hdr).start()",
                    "ok = bool(res) and res.get('error') is None",
                    "print(json.dumps({'ok': ok, 'pl': pl, 'trace': '->'.join(t), 'error': (res or {}).get('error') if isinstance(res, dict) else None, 'watch': watch_ref, 'eid': eid}))"
            );
            ProcessBuilder pb = new ProcessBuilder(pythonExe(repo), "-c", code, repo, base, numericId, (slug != null ? slug : ""), String.valueOf(season), String.valueOf(episode), outputPath);
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            java.nio.file.Path logFile = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "sc_python_debug.log");
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()));
                 java.io.BufferedWriter logWriter = java.nio.file.Files.newBufferedWriter(logFile, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                String last = null; String lastJson = null; String line; 
                while ((line = br.readLine()) != null) {
                    logWriter.write(line);
                    logWriter.newLine();
                    last = line; String t = line.trim();
                    if (t.startsWith("{") && t.contains("\"ok\"")) lastJson = t;
                }
                logWriter.flush();
                pr.waitFor();
                boolean ok = false; String trace = null; String playlist = null; String watchRef = null;
                if (lastJson != null) {
                    try {
                        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(lastJson).getAsJsonObject();
                        if (obj.has("ok")) ok = obj.get("ok").getAsBoolean();
                        if (obj.has("trace") && !obj.get("trace").isJsonNull()) trace = obj.get("trace").getAsString();
                        if (obj.has("pl") && !obj.get("pl").isJsonNull()) playlist = obj.get("pl").getAsString();
                        if (obj.has("watch") && !obj.get("watch").isJsonNull()) watchRef = obj.get("watch").getAsString();
                    } catch (Exception ignore) {}
                } else {
                    ok = last != null && last.trim().equals("OK");
                }
                lastPyTrace = trace;
                System.err.println("=== Python debug log: " + logFile.toAbsolutePath());
                System.err.println("=== lastJson: " + lastJson);
                System.err.println("=== trace: " + trace);
                System.err.println("=== playlist: " + playlist);
                if (!ok && playlist != null && !playlist.isBlank()) {
                    System.err.println("=== Attempting FFmpeg fallback...");
                    try {
                        java.nio.file.Path outPath = java.nio.file.Paths.get(outputPath);
                        java.nio.file.Path dir = outPath.getParent();
                        String fname = outPath.getFileName().toString();
                        java.util.Map<String,String> hdr = new java.util.LinkedHashMap<>();
                        hdr.put("User-Agent", ua());
                        // Vixcloud richiede Referer dal dominio vixcloud
                        String plHost = java.net.URI.create(playlist).getHost();
                        if (plHost != null && plHost.contains("vixcloud")) {
                            hdr.put("Referer", "https://" + plHost + "/");
                            hdr.put("Origin", "https://" + plHost);
                        } else {
                            if (watchRef != null && !watchRef.isBlank()) hdr.put("Referer", watchRef);
                            hdr.put("Origin", base);
                        }
                        System.err.println("=== FFmpeg downloading to: " + outPath);
                        System.err.println("=== FFmpeg playlist: " + playlist);
                        System.err.println("=== FFmpeg headers: " + hdr);
                        
                        int speed = com.topent3r.multi.download.DownloadManager.getInstance().getDownloadSpeed();
                        com.topent3r.multi.m3u.services.HttpDownloader downloader = new com.topent3r.multi.m3u.services.HttpDownloader();
                        downloader.downloadWithFfmpeg(playlist, outPath, hdr, new com.topent3r.multi.m3u.services.HttpDownloader.ProgressCallback() {
                            private long lastSecs = 0;
                            @Override
                            public void onProgress(long seconds) {
                                if (seconds != lastSecs) {
                                    String msg = "⏱️ " + formatTime(seconds);
                                    System.err.println("=== FFmpeg progress: " + msg);
                                    if (callback != null) callback.onProgress(msg);
                                    lastSecs = seconds;
                                }
                            }
                            @Override
                            public void onSpeed(String speed) {
                                System.err.println("=== FFmpeg speed: " + speed);
                                if (callback != null) callback.onSpeed(speed);
                            }
                            @Override
                            public void onComplete(long bytes) {
                                String msg = "✅ " + formatSize(bytes);
                                System.err.println("=== FFmpeg complete: " + msg);
                                if (callback != null) callback.onProgress(msg);
                            }
                        }, speed);
                        
                        System.err.println("=== FFmpeg SUCCESS");
                        ok = true;
                    } catch (Exception e) { 
                        System.err.println("=== FFmpeg FAILED: " + e.getMessage());
                        e.printStackTrace();
                        ok = false; 
                    }
                }
                if (!ok && slug != null && !slug.startsWith("it-")) {
                    return pythonDownloadBySE(base, numericId, "it-" + slug, season, episode, outputPath, callback);
                }
                return ok;
            }
        } catch (Exception ignore) { return false; }
    }

    private String pythonExe(String repo) {
        try {
            String env = System.getenv("SC_PYTHON");
            if (env != null && !env.isBlank()) return env;
            java.nio.file.Path p1 = java.nio.file.Paths.get(repo, ".venv", "bin", "python3");
            if (java.nio.file.Files.exists(p1)) return p1.toString();
            java.nio.file.Path p2 = java.nio.file.Paths.get(repo, ".venv", "bin", "python");
            if (java.nio.file.Files.exists(p2)) return p2.toString();
            java.nio.file.Path p3 = java.nio.file.Paths.get(repo, ".venv", "Scripts", "python.exe");
            if (java.nio.file.Files.exists(p3)) return p3.toString();
        } catch (Exception ignore) {}
        return "python3";
    }

    private String pythonResolvePlaylistBySE(String base, String numericId, String slug, Integer season, Integer episode) {
        String repo = "/Users/andreacassan/AndroidStudioProjects/evolutix-panel 2/Downloader/StreamingCommunity/StreamingCommunity-main";
        try {
            if (season == null || episode == null) return null;
            java.nio.file.Path p = java.nio.file.Paths.get(repo);
            if (!java.nio.file.Files.exists(p)) return null;
            String code = String.join("\n",
                    "import sys, json, os, types, importlib.util",
                    "root=os.path.abspath(sys.argv[1])",
                    "pkg=os.path.join(root, 'StreamingCommunity')",
                    "sys.path.insert(0, root)",
                    "sys.path.insert(0, pkg)",
                    "m=types.ModuleType('StreamingCommunity'); m.__path__=[pkg]; sys.modules['StreamingCommunity']=m",
                    "scrape_path=os.path.join(pkg,'Api','Site','streamingcommunity','util','ScrapeSerie.py')",
                    "spec=importlib.util.spec_from_file_location('ScrapeSerie', scrape_path)",
                    "ScrapeSerie=importlib.util.module_from_spec(spec); spec.loader.exec_module(ScrapeSerie)",
                    "GetSerieInfo=ScrapeSerie.GetSerieInfo",
                    "from StreamingCommunity.Api.Player.vixcloud import VideoSource",
                    "from bs4 import BeautifulSoup",
                    "import StreamingCommunity.Util.http_client as http_client",
                    "from StreamingCommunity.Util.headers import get_userAgent",
                    "base=sys.argv[2]",
                    "mid=int(sys.argv[3])",
                    "slug=sys.argv[4]",
                    "sn=int(sys.argv[5])",
                    "en=int(sys.argv[6])",
                    "gs=GetSerieInfo(base+'/it', mid, slug)",
                    "gs.getNumberSeason()",
                    "ep=gs.selectEpisode(sn, en-1)",
                    "vs=VideoSource(base+'/it', True, mid)",
                    "script=None",
                    "try:",
                    "  vs.get_iframe(ep['id']); vs.get_content(); script='ok'",
                    "except Exception as _:",
                    "  pass",
                    "if script is None:",
                    "    sess=http_client.create_client_curl(headers={'User-Agent': get_userAgent()}, allow_redirects=True)",
                    "    embed=sess.get(base+'/it/embed-url/'+str(ep['id'])).text.strip()",
                    "    watch_ref=base+'/it/watch/'+str(mid)+'?e='+str(ep['id'])",
                    "    sess.headers.update({'Referer': watch_ref, 'Origin': base})",
                    "    resp=sess.get(embed)",
                    "    soup=BeautifulSoup(resp.text, 'html.parser')",
                    "    scr=None",
                    "    for tag in soup.find_all('script'):",
                    "        try:",
                    "            txt=tag.text or ''",
                    "            if 'masterPlaylist' in txt or 'video' in txt:",
                    "                scr=txt; break",
                    "        except Exception:",
                    "            pass",
                    "    if scr:",
                    "        vs.parse_script(scr)",
                    "    pl=vs.get_playlist()",
                    "    if not pl:",
                    "        txt=resp.text",
                    "        import re",
                    "        m=re.search(r'https?://[^\\s\\x22\\x27<>]+\\.m3u8[^\\s\\x22\\x27<>]*', txt)",
                    "        pl=m.group(0) if m else None",
                    "print(json.dumps({'playlist': pl}))"
            );
            ProcessBuilder pb = new ProcessBuilder(pythonExe(repo), "-c", code, repo, base, numericId, (slug != null ? slug : ""), String.valueOf(season), String.valueOf(episode));
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                String line, lastJson = null, last = null;
                while ((line = br.readLine()) != null) {
                    last = line;
                    String t = line.trim();
                    if (t.startsWith("{") && t.contains("playlist")) lastJson = t;
                }
                pr.waitFor();
                String out = lastJson != null ? lastJson : last;
                if (out == null) return null;
                try {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(out).getAsJsonObject();
                    String url = obj.has("playlist") && !obj.get("playlist").isJsonNull() ? obj.get("playlist").getAsString() : null;
                    if (url != null && !url.isBlank()) return url;
                    if (slug != null && !slug.startsWith("it-")) {
                        // retry with it- prefix
                        return pythonResolvePlaylistBySE(base, numericId, "it-" + slug, season, episode);
                    }
                    return null;
                } catch (Exception ignore) { return null; }
            }
        } catch (Exception ignore) { return null; }
    }

    private String pythonResolvePlaylist(String base, String numericId, String episodeId) {
        // Usa il repo Python per risolvere la master playlist con lo stesso flusso (VideoSource)
        String repo = "/Users/andreacassan/AndroidStudioProjects/evolutix-panel 2/Downloader/StreamingCommunity/StreamingCommunity-main";
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(repo);
            if (!java.nio.file.Files.exists(p)) return null;
            String code = String.join("\n",
                    "import sys, json, os, types, importlib.util",
                    "root=os.path.abspath(sys.argv[1])",
                    "pkg=os.path.join(root, 'StreamingCommunity')",
                    "sys.path.insert(0, root)",
                    "sys.path.insert(0, pkg)",
                    "m=types.ModuleType('StreamingCommunity'); m.__path__=[pkg]; sys.modules['StreamingCommunity']=m",
                    "from StreamingCommunity.Api.Player.vixcloud import VideoSource",
                    "from bs4 import BeautifulSoup",
                    "import StreamingCommunity.Util.http_client as http_client",
                    "from StreamingCommunity.Util.headers import get_userAgent",
                    "base=sys.argv[2]",
                    "mid=int(sys.argv[3])",
                    "eid=int(sys.argv[4])",
                    "vs=VideoSource(base+'/it', True, mid)",
                    "ok=False; pl=None",
                    "try:\n  vs.get_iframe(eid); vs.get_content(); pl=vs.get_playlist(); ok=True\nexcept Exception as _:\n  ok=False",
                    "if not ok:",
                    "    sess=http_client.create_client_curl(headers={'User-Agent': get_userAgent()}, allow_redirects=True)",
                    "    embed=sess.get(base+'/it/embed-url/'+str(eid)).text.strip()",
                    "    watch_ref=base+'/it/watch/'+str(mid)+'?e='+str(eid)",
                    "    sess.headers.update({'Referer': watch_ref, 'Origin': base})",
                    "    resp=sess.get(embed)",
                    "    soup=BeautifulSoup(resp.text, 'html.parser')",
                    "    scr=None",
                    "    for tag in soup.find_all('script'):",
                    "        try:",
                    "            txt=tag.text or ''",
                    "            if 'masterPlaylist' in txt or 'video' in txt:",
                    "                scr=txt; break",
                    "        except Exception:",
                    "            pass",
                    "    if scr:",
                    "        vs.parse_script(scr)",
                    "    pl=vs.get_playlist()",
                    "    if not pl:",
                    "        txt=resp.text",
                    "        import re",
                    "        m=re.search(r'https?://[^\\s\\x22\\x27<>]+\\.m3u8[^\\s\\x22\\x27<>]*', txt)",
                    "        pl=m.group(0) if m else None",
                    "print(json.dumps({'playlist': pl or ''}))"
            );
            ProcessBuilder pb = new ProcessBuilder(pythonExe(repo), "-c", code, repo, base, numericId, episodeId);
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                String line, lastJson = null, last = null;
                while ((line = br.readLine()) != null) {
                    last = line;
                    String t = line.trim();
                    if (t.startsWith("{") && t.contains("playlist")) lastJson = t;
                }
                pr.waitFor();
                String out = lastJson != null ? lastJson : last;
                if (out == null) return null;
                try {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(out).getAsJsonObject();
                    String url = obj.has("playlist") && !obj.get("playlist").isJsonNull() ? obj.get("playlist").getAsString() : null;
                    return (url==null||url.isBlank()) ? null : url;
                } catch (Exception ignore) { return null; }
            }
        } catch (Exception ignore) { return null; }
    }

    private JsonObject fetchInertiaJson(String url, String version) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", ua())
                    .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("x-inertia", "true")
                    .header("x-inertia-version", version == null ? "" : version)
                    .build();
            try (Response r = httpClient.newCall(req).execute()) {
                if (r.body() == null) return null;
                String body = r.body().string();
                String ct = r.header("Content-Type", "");
                if (ct.contains("json") || body.trim().startsWith("{")) {
                    return JsonParser.parseString(body).getAsJsonObject();
                } else {
                    int appIdx = body.indexOf("id=\"app\"");
                    if (appIdx < 0) appIdx = body.indexOf("id='app'");
                    if (appIdx >= 0) {
                        int dp = body.indexOf("data-page=\"", appIdx);
                        if (dp >= 0) {
                            int start = dp + "data-page=\"".length();
                            int end = body.indexOf('"', start);
                            if (end > start) {
                                String encoded = body.substring(start, end);
                                String json = htmlDecode(encoded);
                                return JsonParser.parseString(json).getAsJsonObject();
                            }
                        } else {
                            dp = body.indexOf("data-page='", appIdx);
                            if (dp >= 0) {
                                int start = dp + "data-page='".length();
                                int end = body.indexOf('\'', start);
                                if (end > start) {
                                    String encoded = body.substring(start, end);
                                    String json = htmlDecode(encoded);
                                    return JsonParser.parseString(json).getAsJsonObject();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private JsonObject fetchAppDataPage(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", ua())
                    .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                    .build();
            try (Response r = httpClient.newCall(req).execute()) {
                if (r.body() == null) return null;
                String body = r.body().string();
                int appIdx = body.indexOf("id=\"app\"");
                if (appIdx < 0) appIdx = body.indexOf("id='app'");
                if (appIdx >= 0) {
                    int dp = body.indexOf("data-page=\"", appIdx);
                    if (dp >= 0) {
                        int start = dp + "data-page=\"".length();
                        int end = body.indexOf('"', start);
                        if (end > start) {
                            String encoded = body.substring(start, end);
                            String json = htmlDecode(encoded);
                            return JsonParser.parseString(json).getAsJsonObject();
                        }
                    } else {
                        dp = body.indexOf("data-page='", appIdx);
                        if (dp >= 0) {
                            int start = dp + "data-page='".length();
                            int end = body.indexOf('\'', start);
                            if (end > start) {
                                String encoded = body.substring(start, end);
                                String json = htmlDecode(encoded);
                                return JsonParser.parseString(json).getAsJsonObject();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private JsonArray findEpisodesArray(JsonObject root) {
        if (root == null) return null;
        JsonElement propsEl = root.get("props");
        if (propsEl != null && propsEl.isJsonObject()) {
            JsonArray arr = scanForEpisodesArray(propsEl);
            if (arr != null) return arr;
        }
        return scanForEpisodesArray(root);
    }

    private JsonArray scanForEpisodesArray(JsonElement node) {
        if (node == null || node.isJsonNull()) return null;
        if (node.isJsonArray()) {
            JsonArray a = node.getAsJsonArray();
            if (a.size() > 0 && a.get(0).isJsonObject()) {
                JsonObject first = a.get(0).getAsJsonObject();
                boolean hasNumber = first.has("number") || first.has("episode") || first.has("episode_number") || first.has("episodeNumber");
                boolean hasTitle = first.has("title") || first.has("name") || first.has("episode_title");
                if (hasNumber && hasTitle) return a;
            }
        } else if (node.isJsonObject()) {
            for (String k : node.getAsJsonObject().keySet()) {
                JsonArray candidate = scanForEpisodesArray(node.getAsJsonObject().get(k));
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private static String firstNonNull(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String originOf(String url) {
        try { java.net.URI u = new java.net.URI(url); return u.getScheme() + "://" + u.getHost(); } catch (Exception e) { return null; }
    }

    private static class InMemoryCookieJar implements CookieJar {
        private final Map<String, java.util.List<Cookie>> store = new ConcurrentHashMap<>();
        @Override public void saveFromResponse(HttpUrl url, java.util.List<Cookie> cookies) {
            if (cookies == null || cookies.isEmpty()) return;
            String host = url.host();
            store.compute(host, (k, v) -> {
                java.util.List<Cookie> list = (v == null) ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v);
                list.addAll(cookies);
                return list;
            });
        }
        @Override public java.util.List<Cookie> loadForRequest(HttpUrl url) {
            java.util.List<Cookie> out = new java.util.ArrayList<>();
            String host = url.host();
            store.forEach((k, v) -> { if (host.equals(k) || host.endsWith("."+k) || k.endsWith("."+host)) out.addAll(v); });
            return out;
        }
    }

    private String cookieHeaderForUrl(String url) {
        try {
            java.util.List<Cookie> list = cookieStore.loadForRequest(HttpUrl.get(url));
            if (list == null || list.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (int i=0;i<list.size();i++) {
                Cookie c = list.get(i);
                if (i>0) sb.append("; ");
                sb.append(c.name()).append("=").append(c.value());
            }
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private String fetchEmbedUrl(String baseIt, String episodeId) {
        try {
            String url = baseIt + "/embed-url/" + URLEncoder.encode(episodeId, StandardCharsets.UTF_8);
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", ua())
                    .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                    .header("Accept", "text/plain, */*;q=0.1")
                    .build();
            try (Response r = httpClient.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return null;
                String txt = r.body().string();
                return txt == null ? null : txt.trim();
            }
        } catch (Exception ignore) { return null; }
    }

    private String parseMp4FromScript(String script) {
        if (script == null) return null;
        try {
            Matcher m = Pattern.compile("src_mp4\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE).matcher(script);
            if (m.find()) return m.group(1);
            m = Pattern.compile("https?://[^'\"\\s>]+\\.mp4", Pattern.CASE_INSENSITIVE).matcher(script);
            if (m.find()) return m.group(0);
        } catch (Exception ignore) {}
        return null;
    }

    private String findAnyM3u8(String text) {
        if (text == null) return null;
        try {
            Matcher m = Pattern.compile("https?://[^'\"\\s<>]+\\.m3u8[^'\"\\s<>]*", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) return m.group(0);
        } catch (Exception ignore) {}
        return null;
    }

    private void collectSeasonEpisodes(String base, String idSlug, String sNum, String version, List<Episode> out) {
        try {
            String seasonUrl = base + "/it/titles/" + idSlug + "/season-" + sNum;
            JsonObject seasonJson = fetchInertiaJson(seasonUrl, version);
            if (seasonJson == null) return;
            JsonObject sProps = seasonJson.has("props") && seasonJson.get("props").isJsonObject() ? seasonJson.getAsJsonObject("props") : null;
            if (sProps == null) return;
            JsonObject loadedSeason = sProps.has("loadedSeason") && sProps.get("loadedSeason").isJsonObject() ? sProps.getAsJsonObject("loadedSeason") : null;
            if (loadedSeason == null) return;
            JsonArray epsArr = loadedSeason.has("episodes") && loadedSeason.get("episodes").isJsonArray() ? loadedSeason.getAsJsonArray("episodes") : null;
            if (epsArr == null) return;
            for (JsonElement e : epsArr) {
                if (!e.isJsonObject()) continue;
                JsonObject eo = e.getAsJsonObject();
                String eid = firstNonNull(
                        getAsString(eo, "id"),
                        getAsString(eo, "episode_id"),
                        getAsString(eo, "video_id"),
                        getAsString(eo, "stream_id")
                );
                String en = firstNonNull(getAsString(eo, "number"), getAsString(eo, "episode_number"), getAsString(eo, "episode"));
                String et = firstNonNull(getAsString(eo, "title"), getAsString(eo, "name"));
                if ((eid == null || eid.isBlank())) {
                    String eurl = getAsString(eo, "url");
                    if (eurl != null) {
                        Matcher mm = Pattern.compile("(\\d+)(?:$|[^0-9])").matcher(eurl);
                        String last = null; while (mm.find()) { last = mm.group(1); }
                        if (last != null) eid = last;
                    }
                }
                if (en == null || en.isBlank()) continue;
                if (et == null) et = "";
                out.add(new Episode(eid, sNum, en, et));
            }
        } catch (Exception ignore) {}
    }

    // File naming moved to FileNameFormatter utility class

    private String extractIframeSrc(String iframePageUrl, String referer) throws Exception {
        Request req = new Request.Builder().url(iframePageUrl)
                .header("User-Agent", ua())
                .header("Accept-Language","it-IT,it;q=0.9")
                .header("Referer", referer == null ? iframePageUrl : referer)
                .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("sec-ch-ua", "\"Chromium\";v=\"128\", \"Not=A?Brand\";v=\"99\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"macOS\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Upgrade-Insecure-Requests", "1")
                .build();
        try (Response r = httpClient.newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new IllegalStateException("HTTP " + r.code());
            String html = r.body().string();
            Matcher m = Pattern.compile("<iframe[^>]+src=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE).matcher(html);
            String src = null;
            if (m.find()) src = m.group(1);
            if (src == null) {
                m = Pattern.compile("<iframe[^>]+src='([^']+)'", Pattern.CASE_INSENSITIVE).matcher(html);
                if (m.find()) src = m.group(1);
            }
            if (src == null) return null;
            // resolve absolute
            if (src.startsWith("//")) return "https:" + src;
            if (src.startsWith("http://") || src.startsWith("https://")) return src;
            // relative path -> resolve from iframePageUrl
            try {
                java.net.URI base = new java.net.URI(iframePageUrl);
                if (src.startsWith("/")) {
                    return base.getScheme() + "://" + base.getHost() + src;
                } else {
                    String path = base.getPath();
                    if (!path.endsWith("/")) path = path.substring(0, path.lastIndexOf('/') + 1);
                    return base.getScheme() + "://" + base.getHost() + path + src;
                }
            } catch (Exception ignore) { return src; }
        }
    }

    private String fetchIframeScript(String iframeUrl, String referer) throws Exception {
        Request req = new Request.Builder().url(iframeUrl)
                .header("User-Agent", ua())
                .header("Accept-Language","it-IT,it;q=0.9")
                .header("Referer", referer == null ? iframeUrl : referer)
                .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("sec-ch-ua", "\"Chromium\";v=\"128\", \"Not=A?Brand\";v=\"99\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"macOS\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Upgrade-Insecure-Requests", "1")
                .build();
        try (Response r = httpClient.newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new IllegalStateException("HTTP " + r.code());
            String html = r.body().string();
            // Try to find the script that contains masterPlaylist
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("<script[^>]*>([\\s\\S]*?)</script>", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher it = p.matcher(html);
            String first = null;
            while (it.find()) {
                String s = it.group(1);
                if (first == null) first = s;
                if (s != null && s.contains("masterPlaylist")) return s;
            }
            return first; // fallback
        }
    }

    private static class MasterPlaylist { String url; String token; String expires; boolean canFhd; boolean bParam; }

    private MasterPlaylist parseMasterPlaylist(String script) {
        try {
            MasterPlaylist mp = new MasterPlaylist();
            // canPlayFHD
            Matcher fhd = Pattern.compile("canPlayFHD\\s*:\\s*true").matcher(script);
            mp.canFhd = fhd.find();
            // Case A: masterPlaylist present as direct string URL
            Matcher direct = Pattern.compile("[\\\"']?masterPlaylist[\\\"']?\\s*:\\s*['\"](https?:[^'\"]+m3u8[^'\"]*)['\"]").matcher(script);
            if (direct.find()) {
                mp.url = direct.group(1);
                return mp; // no token/expires needed
            }
            // url (with or without quotes around key)
            Matcher u = Pattern.compile("[\\\"']?url[\\\"']?\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(script);
            if (u.find()) mp.url = u.group(1);
            // token
            Matcher t = Pattern.compile("[\\\"']?token[\\\"']?\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(script);
            if (t.find()) mp.token = t.group(1);
            // expires (number or string)
            Matcher ex = Pattern.compile("[\\\"']?expires[\\\"']?\\s*:\\s*([0-9]+|['\"][^'\"]+['\"])\\s*[,}]\\s*").matcher(script);
            if (ex.find()) mp.expires = ex.group(1).replace("'", "").replace("\"", "");
            // b=1 present in url already?
            if (mp.url != null && mp.url.contains("b=1")) mp.bParam = true;
            if (mp.url == null || mp.token == null || mp.expires == null) return null;
            return mp;
        } catch (Exception ignore) { return null; }
    }

    private String buildPlaylistUrl(MasterPlaylist mp) {
        String base = mp.url;
        String sep = base.contains("?") ? "&" : "?";
        StringBuilder sb = new StringBuilder(base).append(sep)
                .append("token=").append(URLEncoder.encode(mp.token, StandardCharsets.UTF_8))
                .append("&expires=").append(URLEncoder.encode(mp.expires, StandardCharsets.UTF_8));
        if (mp.canFhd) sb.append("&h=1");
        if (mp.bParam) sb.append("&b=1");
        return sb.toString();
    }

    // --- Utilities needed by JSON/page parsers ---
    private static String htmlDecode(String s) {
        if (s == null) return null;
        String out = s
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&#60;", "<")
                .replace("&gt;", ">")
                .replace("&#62;", ">")
                .replace("&amp;", "&");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(x?[0-9A-Fa-f]+);").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group(1);
            int cp;
            try {
                if (g.startsWith("x") || g.startsWith("X")) cp = Integer.parseInt(g.substring(1), 16);
                else cp = Integer.parseInt(g, 10);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Exception e) {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String getAsString(JsonObject obj, String key) {
        if (obj == null || key == null) return null;
        if (!obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try { return el.getAsString(); } catch (Exception ignore) { return null; }
    }

    private void scanTitlesForItems(JsonElement node, java.util.Map<String, MediaItem> out) {
        if (node == null || node.isJsonNull()) return;
        if (node.isJsonArray()) {
            for (JsonElement el : node.getAsJsonArray()) scanTitlesForItems(el, out);
            return;
        }
        if (!node.isJsonObject()) return;
        JsonObject o = node.getAsJsonObject();
        String url = getAsString(o, "url");
        if (url != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("/it/titles/(\\d+)-([A-Za-z0-9-]+)").matcher(url);
            if (m.find()) {
                String mid = m.group(1);
                String slug = m.group(2);
                String idSlug = mid + "-" + slug;
                if (!out.containsKey(idSlug)) {
                    String title = firstNonNull(getAsString(o, "title"), getAsString(o, "name"));
                    String year = firstNonNull(getAsString(o, "year"), getAsString(o, "release_year"));
                    String type = firstNonNull(getAsString(o, "type"), getAsString(o, "category"));
                    if (type == null || type.isBlank()) type = "Serie";
                    out.put(idSlug, new MediaItem(idSlug, title == null ? slug : title, type, "StreamingCommunity", "SC", year));
                }
            }
        }
        if (url == null) {
            String mid = getAsString(o, "id");
            String slug = firstNonNull(getAsString(o, "slug"), getAsString(o, "seo_slug"), getAsString(o, "titleSlug"));
            if (mid != null && slug != null) {
                java.util.regex.Matcher midM = java.util.regex.Pattern.compile("^\\d+$").matcher(mid);
                java.util.regex.Matcher slugM = java.util.regex.Pattern.compile("^[A-Za-z0-9-]+$").matcher(slug);
                if (midM.find() && slugM.find()) {
                    String idSlug = mid + "-" + slug;
                    if (!out.containsKey(idSlug)) {
                        String title = firstNonNull(getAsString(o, "title"), getAsString(o, "name"));
                        String year = firstNonNull(getAsString(o, "year"), getAsString(o, "release_year"));
                        String type = firstNonNull(getAsString(o, "type"), getAsString(o, "category"));
                        if (type == null || type.isBlank()) type = "Serie";
                        out.put(idSlug, new MediaItem(idSlug, title == null ? slug : title, type, "StreamingCommunity", "SC", year));
                    }
                }
            }
        }
        for (String k : o.keySet()) scanTitlesForItems(o.get(k), out);
    }

    private void addTitlesFromInertia(JsonObject root, java.util.Map<String, MediaItem> out) {
        if (root == null) return;
        JsonObject props = root.has("props") && root.get("props").isJsonObject() ? root.getAsJsonObject("props") : null;
        if (props == null) return;
        if (props.has("titles") && props.get("titles").isJsonArray()) {
            JsonArray arr = props.getAsJsonArray("titles");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject t = el.getAsJsonObject();
                String mid = getAsString(t, "id");
                String slug = firstNonNull(getAsString(t, "slug"), getAsString(t, "seo_slug"));
                if (mid == null || slug == null) continue;
                if (!mid.matches("\\d+") || !slug.matches("[A-Za-z0-9-]+")) continue;
                String idSlug = mid + "-" + slug;
                if (!out.containsKey(idSlug)) {
                    String title = firstNonNull(getAsString(t, "name"), getAsString(t, "title"), slug);
                    String year = firstNonNull(getAsString(t, "last_air_date"), getAsString(t, "release_date"), getAsString(t, "year"));
                    String type = firstNonNull(getAsString(t, "type"), "Serie");
                    out.put(idSlug, new MediaItem(idSlug, title, type, "StreamingCommunity", "SC", year));
                }
            }
        }
    }

    private String getInertiaVersion(String base) {
        try {
            JsonObject home = fetchAppDataPage(base + "/it");
            if (home != null && home.has("version")) {
                return getAsString(home, "version");
            }
        } catch (Exception ignore) {}
        return null;
    }

    private java.util.List<MediaItem> pythonSearch(String base, String query) {
        java.util.List<MediaItem> out = new java.util.ArrayList<>();
        String repo = "/Users/andreacassan/AndroidStudioProjects/evolutix-panel 2/Downloader/StreamingCommunity/StreamingCommunity-main";
        try {
            if (query == null || query.isBlank()) return out;
            java.nio.file.Path p = java.nio.file.Paths.get(repo);
            if (!java.nio.file.Files.exists(p)) return out;
            String code = String.join("\n",
                    "import sys, os, json, types, importlib.util, re",
                    "root=os.path.abspath(sys.argv[1])",
                    "pkg=os.path.join(root, 'StreamingCommunity')",
                    "sys.path.insert(0, root); sys.path.insert(0, pkg)",
                    "m=types.ModuleType('StreamingCommunity'); m.__path__=[pkg]; sys.modules['StreamingCommunity']=m",
                    "from bs4 import BeautifulSoup",
                    "import StreamingCommunity.Util.http_client as http_client",
                    "from StreamingCommunity.Util.headers import get_userAgent",
                    "base=sys.argv[2]",
                    "q=sys.argv[3]",
                    "sess=http_client.create_client_curl(headers={'User-Agent': get_userAgent()}, allow_redirects=True)",
                    "home=sess.get(base+'/it').text",
                    "version=None",
                    "m=re.search(r'data-page=\\\"([^\\\"]+)\\\"', home)",
                    "if not m: m=re.search(r\"data-page='([^']+)'\", home)",
                    "if m:",
                    "  try:",
                    "    version=json.loads(m.group(1)).get('version')",
                    "  except Exception: pass",
                    "res=[]",
                    "if version:",
                    "  hdr={'User-Agent': get_userAgent(), 'x-inertia':'true','x-inertia-version':version,'Accept':'application/json, text/plain, */*'}",
                    "  r=sess.get(base+'/it/search?q='+q, headers=hdr)",
                    "  try:",
                    "    obj=r.json() if hasattr(r,'json') else json.loads(r.text)",
                    "    titles=obj.get('props',{}).get('titles',[])",
                    "    for t in titles:",
                    "      mid=str(t.get('id')) if t.get('id') is not None else None",
                    "      slug=t.get('slug') or t.get('seo_slug')",
                    "      name=t.get('name') or t.get('title') or slug",
                    "      year=t.get('last_air_date') or t.get('release_date') or t.get('year')",
                    "      typ=t.get('type') or 'Serie'",
                    "      if mid and slug: res.append({'id': f'{mid}-{slug}', 'title': name, 'type': typ, 'year': year})",
                    "  except Exception: pass",
                    "print(json.dumps(res))"
            );
            ProcessBuilder pb = new ProcessBuilder(pythonExe(repo), "-c", code, repo, base, query);
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line; while ((line = br.readLine()) != null) sb.append(line);
                pr.waitFor();
                String txt = sb.toString().trim();
                if (txt.isEmpty()) return out;
                try {
                    com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(txt).getAsJsonArray();
                    for (JsonElement el : arr) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        String id = getAsString(o, "id");
                        String title = getAsString(o, "title");
                        String type = getAsString(o, "type");
                        String year = getAsString(o, "year");
                        if (id != null) out.add(new MediaItem(id, title, type==null?"Serie":type, "StreamingCommunity", "SC", year));
                    }
                } catch (Exception ignore) { }
            }
        } catch (Exception ignore) {}
        return out;
    }

    private static Integer parseIntSafe(String s) {
        if (s == null) return null;
        try {
            String digits = s.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) return null;
            return Integer.parseInt(digits);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String formatTime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
