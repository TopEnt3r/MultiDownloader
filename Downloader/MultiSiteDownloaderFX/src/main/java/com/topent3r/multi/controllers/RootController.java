package com.topent3r.multi.controllers;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

import java.io.IOException;

public class RootController {

    @FXML
    private TabPane rootTabPane;

    @FXML
    public void initialize() {
        // Inizializza contenuti dei tab principali
        loadTabContent("M3U Downloader", "/com/topent3r/multi/tabs/M3UTabView.fxml");
        loadTabContent("StreamingCommunity", "/com/topent3r/multi/tabs/StreamingCommunityView.fxml");
        loadTabContent("AnimeUnity", "/com/topent3r/multi/tabs/AnimeUnityView.fxml");
        loadTabContent("AltaDefinizione", "/com/topent3r/multi/tabs/AltaDefinizioneView.fxml");
        loadTabContent("MediasetInfinity", "/com/topent3r/multi/tabs/MediasetInfinityView.fxml");
        loadTabContent("GuardaSerie", "/com/topent3r/multi/tabs/GuardaSerieView.fxml");
        loadTabContent("RaiPlay", "/com/topent3r/multi/tabs/RaiPlayView.fxml");
        loadTabContent("AnimeWorld", "/com/topent3r/multi/tabs/AnimeWorldView.fxml");
        loadTabContent("Crunchyroll", "/com/topent3r/multi/tabs/CrunchyrollView.fxml");
        loadTabContent("Downloads", "/com/topent3r/multi/tabs/DownloadsView.fxml");
        loadTabContent("Settings", "/com/topent3r/multi/tabs/SettingsView.fxml");
    }

    private void loadTabContent(String tabTitle, String fxmlPath) {
        for (Tab tab : rootTabPane.getTabs()) {
            if (tabTitle.equals(tab.getText())) {
                try {
                    BorderPane content = FXMLLoader.load(getClass().getResource(fxmlPath));
                    tab.setContent(content);
                } catch (IOException e) {
                    tab.setContent(new BorderPane());
                }
                break;
            }
        }
    }
}

