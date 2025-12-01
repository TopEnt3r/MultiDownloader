package com.topent3r.multi.m3u.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Properties;

/** SettingsManager semplice basato su java.util.Properties. */
public class SettingsManager {

    private final Path file;

    /** Dati configurazione usati dal Controller */
    public static class Settings {
        public String lastUrl = "";
        public String downloadDir = "";
        public int downloadSpeed = 2; // 1=x1, 2=x2, 4=x4, 8=x8
        public String downloadQuality = "720p"; // Best, 1080p, 720p, 480p, 360p
        
        // Site URLs (configurable) - defaults from domains.json
        public String urlStreamingCommunity = "https://streamingcommunityz.ltd";
        public String urlRaiPlay = "https://www.raiplay.it";
        public String urlMediasetInfinity = "https://mediasetinfinity.mediaset.it";
        public String urlCrunchyroll = "https://www.crunchyroll.com";
        public String urlAnimeUnity = "https://www.animeunity.so";
        public String urlAltaDefinizione = "https://altadefinizionegratis.lol";
        public String urlGuardaSerie = "https://guardaserietv.cfd";
        public String urlAnimeWorld = "https://www.animeworld.ac";
        
        // Crunchyroll login (requires device_id and etp_rt token)
        public String crunchyrollDeviceId = "";
        public String crunchyrollEtpRt = "";
    }

    /** Costruttore con file esplicito */
    public SettingsManager(Path file) {
        this.file = file;
    }

    /** Costruttore di default: ~/.topent3r/settings.properties */
    public SettingsManager() {
        this(Paths.get(System.getProperty("user.home"), ".topent3r", "settings.properties"));
    }

    /** Carica le impostazioni dal file (se esiste) */
    public Settings load() {
        Settings s = new Settings();
        try {
            if (Files.exists(file)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
                s.lastUrl     = p.getProperty("lastUrl", "");
                s.downloadDir = p.getProperty("downloadDir", "");
                s.downloadSpeed = Integer.parseInt(p.getProperty("downloadSpeed", "2"));
                s.downloadQuality = p.getProperty("downloadQuality", "720p");
                
                // Load site URLs
                s.urlStreamingCommunity = p.getProperty("urlStreamingCommunity", s.urlStreamingCommunity);
                s.urlRaiPlay = p.getProperty("urlRaiPlay", s.urlRaiPlay);
                s.urlMediasetInfinity = p.getProperty("urlMediasetInfinity", s.urlMediasetInfinity);
                s.urlCrunchyroll = p.getProperty("urlCrunchyroll", s.urlCrunchyroll);
                s.urlAnimeUnity = p.getProperty("urlAnimeUnity", s.urlAnimeUnity);
                s.urlAltaDefinizione = p.getProperty("urlAltaDefinizione", s.urlAltaDefinizione);
                s.urlGuardaSerie = p.getProperty("urlGuardaSerie", s.urlGuardaSerie);
                s.urlAnimeWorld = p.getProperty("urlAnimeWorld", s.urlAnimeWorld);
                
                // Load Crunchyroll credentials
                s.crunchyrollDeviceId = p.getProperty("crunchyrollDeviceId", "");
                s.crunchyrollEtpRt = p.getProperty("crunchyrollEtpRt", "");
            }
        } catch (IOException | NumberFormatException ignored) {}
        return s;
    }

    /** Salva le impostazioni sul file */
    public void save(Settings s) throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("lastUrl",     s.lastUrl     == null ? "" : s.lastUrl);
        p.setProperty("downloadDir", s.downloadDir == null ? "" : s.downloadDir);
        p.setProperty("downloadSpeed", String.valueOf(s.downloadSpeed));
        p.setProperty("downloadQuality", s.downloadQuality == null ? "720p" : s.downloadQuality);
        
        // Save site URLs
        p.setProperty("urlStreamingCommunity", s.urlStreamingCommunity != null ? s.urlStreamingCommunity : "");
        p.setProperty("urlRaiPlay", s.urlRaiPlay != null ? s.urlRaiPlay : "");
        p.setProperty("urlMediasetInfinity", s.urlMediasetInfinity != null ? s.urlMediasetInfinity : "");
        p.setProperty("urlCrunchyroll", s.urlCrunchyroll != null ? s.urlCrunchyroll : "");
        p.setProperty("urlAnimeUnity", s.urlAnimeUnity != null ? s.urlAnimeUnity : "");
        p.setProperty("urlAltaDefinizione", s.urlAltaDefinizione != null ? s.urlAltaDefinizione : "");
        p.setProperty("urlGuardaSerie", s.urlGuardaSerie != null ? s.urlGuardaSerie : "");
        p.setProperty("urlAnimeWorld", s.urlAnimeWorld != null ? s.urlAnimeWorld : "");
        
        // Save Crunchyroll credentials
        p.setProperty("crunchyrollDeviceId", s.crunchyrollDeviceId != null ? s.crunchyrollDeviceId : "");
        p.setProperty("crunchyrollEtpRt", s.crunchyrollEtpRt != null ? s.crunchyrollEtpRt : "");
        
        try (OutputStream out = Files.newOutputStream(
                file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            p.store(out, "TopEnt3r settings");
        }
    }
}
