package com.topent3r.multi.controllers;

import com.topent3r.multi.download.DownloadManager;
import com.topent3r.multi.model.Episode;
import com.topent3r.multi.model.MediaItem;
import com.topent3r.multi.services.ContentProvider;
import com.topent3r.multi.services.StreamingWatchProvider;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.util.List;

public class StreamingWatchController {

    @FXML private TextField searchField;
    @FXML private Button searchButton;

    @FXML private TableView<MediaItem> resultsTable;
    @FXML private TableColumn<MediaItem, String> titleColumn;
    @FXML private TableColumn<MediaItem, String> typeColumn;
    @FXML private TableColumn<MediaItem, String> yearColumn;

    @FXML private Button downloadButton;

    private final ContentProvider provider = new StreamingWatchProvider();
    private final ObservableList<MediaItem> results = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        titleColumn.setCellValueFactory(new PropertyValueFactory<>("title"));
        typeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
        yearColumn.setCellValueFactory(new PropertyValueFactory<>("year"));
        resultsTable.setItems(results);

        resultsTable.getSelectionModel().selectedItemProperty().addListener((obs,o,n) -> downloadButton.setDisable(n==null));
        searchButton.setOnAction(e -> onSearch());
        searchField.setOnAction(e -> onSearch());
        downloadButton.setOnAction(e -> onDownloadSelected());
    }

    private void onSearch() {
        String query = searchField.getText();
        if (query == null || query.isBlank()) return;
        
        searchButton.setDisable(true);
        searchField.setDisable(true);
        new Thread(() -> {
            try {
                List<MediaItem> searchResults = provider.search(query);
                Platform.runLater(() -> {
                    results.setAll(searchResults);
                    searchButton.setDisable(false);
                    searchField.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    searchButton.setDisable(false);
                    searchField.setDisable(false);
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Errore ricerca: " + ex.getMessage());
                    alert.showAndWait();
                });
            }
        }, "streamingwatch-search").start();
    }

    private void onDownloadSelected() {
        MediaItem item = resultsTable.getSelectionModel().getSelectedItem();
        if (item == null) return;
        
        downloadButton.setDisable(true);
        new Thread(() -> {
            try {
                List<Episode> episodes = provider.listEpisodes(item);
                Platform.runLater(() -> {
                    DownloadManager.getInstance().enqueue(item, provider, episodes);
                    downloadButton.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    downloadButton.setDisable(false);
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Errore: " + ex.getMessage());
                    alert.showAndWait();
                });
            }
        }, "streamingwatch-add-download").start();
    }
}
