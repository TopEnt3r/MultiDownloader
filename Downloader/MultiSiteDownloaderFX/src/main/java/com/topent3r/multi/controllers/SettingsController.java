package com.topent3r.multi.controllers;

import com.topent3r.multi.m3u.utils.SettingsManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

public class SettingsController {

    // URL fields
    @FXML private TextField urlStreamingCommunity;
    @FXML private TextField urlRaiPlay;
    @FXML private TextField urlMediasetInfinity;
    @FXML private TextField urlCrunchyroll;
    @FXML private TextField urlAnimeUnity;
    @FXML private TextField urlAltaDefinizione;
    @FXML private TextField urlGuardaSerie;
    @FXML private TextField urlAnimeWorld;
    
    // Crunchyroll login fields
    @FXML private TextField crunchyrollDeviceId;
    @FXML private TextField crunchyrollEtpRt;
    
    @FXML private Label statusLabel;

    private final SettingsManager settingsManager = new SettingsManager();
    private SettingsManager.Settings settings;

    @FXML
    public void initialize() {
        settings = settingsManager.load();
        loadUrlsToFields();
    }

    private void loadUrlsToFields() {
        // URLs
        urlStreamingCommunity.setText(settings.urlStreamingCommunity);
        urlRaiPlay.setText(settings.urlRaiPlay);
        urlMediasetInfinity.setText(settings.urlMediasetInfinity);
        urlCrunchyroll.setText(settings.urlCrunchyroll);
        urlAnimeUnity.setText(settings.urlAnimeUnity);
        urlAltaDefinizione.setText(settings.urlAltaDefinizione);
        urlGuardaSerie.setText(settings.urlGuardaSerie);
        urlAnimeWorld.setText(settings.urlAnimeWorld);
        
        // Crunchyroll credentials
        crunchyrollDeviceId.setText(settings.crunchyrollDeviceId);
        crunchyrollEtpRt.setText(settings.crunchyrollEtpRt);
    }

    @FXML
    private void onSave() {
        // URLs
        settings.urlStreamingCommunity = urlStreamingCommunity.getText().trim();
        settings.urlRaiPlay = urlRaiPlay.getText().trim();
        settings.urlMediasetInfinity = urlMediasetInfinity.getText().trim();
        settings.urlCrunchyroll = urlCrunchyroll.getText().trim();
        settings.urlAnimeUnity = urlAnimeUnity.getText().trim();
        settings.urlAltaDefinizione = urlAltaDefinizione.getText().trim();
        settings.urlGuardaSerie = urlGuardaSerie.getText().trim();
        settings.urlAnimeWorld = urlAnimeWorld.getText().trim();
        
        // Crunchyroll credentials
        settings.crunchyrollDeviceId = crunchyrollDeviceId.getText().trim();
        settings.crunchyrollEtpRt = crunchyrollEtpRt.getText().trim();

        try {
            settingsManager.save(settings);
            
            // Update domains.json for StreamingCommunity library
            updateDomainsJson();
            
            statusLabel.setText("Salvato!");
            statusLabel.setStyle("-fx-text-fill: green;");
        } catch (Exception e) {
            statusLabel.setText("Errore: " + e.getMessage());
            statusLabel.setStyle("-fx-text-fill: red;");
        }
    }

    @FXML
    private void onResetDefaults() {
        SettingsManager.Settings defaults = new SettingsManager.Settings();
        // URLs
        urlStreamingCommunity.setText(defaults.urlStreamingCommunity);
        urlRaiPlay.setText(defaults.urlRaiPlay);
        urlMediasetInfinity.setText(defaults.urlMediasetInfinity);
        urlCrunchyroll.setText(defaults.urlCrunchyroll);
        urlAnimeUnity.setText(defaults.urlAnimeUnity);
        urlAltaDefinizione.setText(defaults.urlAltaDefinizione);
        urlGuardaSerie.setText(defaults.urlGuardaSerie);
        urlAnimeWorld.setText(defaults.urlAnimeWorld);
        // Login - keep existing credentials, don't reset
        statusLabel.setText("URL ripristinati (credenziali mantenute, non salvato)");
        statusLabel.setStyle("-fx-text-fill: orange;");
    }
    
    private void updateDomainsJson() {
        try {
            // Path to domains.json
            java.nio.file.Path domainsPath = java.nio.file.Paths.get(
                System.getProperty("user.dir")).getParent()
                .resolve("StreamingCommunity/StreamingCommunity-main/.github/.domain/domains.json");
            
            if (!java.nio.file.Files.exists(domainsPath)) {
                System.err.println("domains.json not found at: " + domainsPath);
                return;
            }
            
            String content = java.nio.file.Files.readString(domainsPath);
            com.google.gson.JsonObject domains = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
            
            // Update each site URL
            updateDomainEntry(domains, "streamingcommunity", settings.urlStreamingCommunity);
            updateDomainEntry(domains, "animeunity", settings.urlAnimeUnity);
            updateDomainEntry(domains, "animeworld", settings.urlAnimeWorld);
            updateDomainEntry(domains, "guardaserie", settings.urlGuardaSerie);
            updateDomainEntry(domains, "altadefinizione", settings.urlAltaDefinizione);
            
            // Write back
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            java.nio.file.Files.writeString(domainsPath, gson.toJson(domains));
            
        } catch (Exception e) {
            System.err.println("Failed to update domains.json: " + e.getMessage());
        }
    }
    
    private void updateDomainEntry(com.google.gson.JsonObject domains, String siteName, String url) {
        if (url == null || url.isBlank()) return;
        try {
            // Extract domain parts from URL like "https://streamingcommunity.computer"
            String cleanUrl = url.replaceAll("/$", ""); // Remove trailing slash
            String fullUrl = cleanUrl.endsWith("/") ? cleanUrl : cleanUrl + "/";
            
            // Extract TLD (last part after last dot)
            String host = cleanUrl.replaceAll("https?://", "").replaceAll("/.*", "");
            String[] parts = host.split("\\.");
            String tld = parts.length > 0 ? parts[parts.length - 1] : "";
            
            if (domains.has(siteName)) {
                com.google.gson.JsonObject entry = domains.getAsJsonObject(siteName);
                String oldDomain = entry.has("domain") ? entry.get("domain").getAsString() : "";
                entry.addProperty("old_domain", oldDomain);
                entry.addProperty("domain", tld);
                entry.addProperty("full_url", fullUrl);
                entry.addProperty("time_change", java.time.LocalDateTime.now().toString());
            }
        } catch (Exception e) {
            System.err.println("Failed to update " + siteName + ": " + e.getMessage());
        }
    }
    
    public SettingsManager.Settings getSettings() {
        return settings;
    }
}
