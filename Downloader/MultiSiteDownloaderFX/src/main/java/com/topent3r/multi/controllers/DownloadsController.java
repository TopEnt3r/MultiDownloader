package com.topent3r.multi.controllers;

import com.topent3r.multi.download.DownloadItem;
import com.topent3r.multi.download.DownloadManager;
import com.topent3r.multi.download.DownloadStatus;
import com.topent3r.multi.model.Episode;
import com.topent3r.multi.m3u.utils.SettingsManager;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.controlsfx.control.CheckComboBox;

import javafx.stage.DirectoryChooser;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DownloadsController {

    @FXML
    private TableView<DownloadItem> downloadsTable;

    @FXML
    private TableColumn<DownloadItem, String> titleColumn;

    @FXML
    private TableColumn<DownloadItem, String> qualityColumn;

    @FXML
    private TableColumn<DownloadItem, String> seasonColumn;

    @FXML
    private TableColumn<DownloadItem, String> episodesColumn;

    @FXML
    private TableColumn<DownloadItem, String> statusColumn;

    @FXML
    private TableColumn<DownloadItem, String> speedColumn;

    @FXML
    private TableColumn<DownloadItem, Double> progressColumn;

    @FXML
    private TableColumn<DownloadItem, Void> actionsColumn;

    @FXML
    private Button startSelectedBtn;

    @FXML
    private Button cancelSelectedBtn;

    @FXML
    private Button removeCompletedBtn;

    @FXML
    private Label downloadDirLabel;

    @FXML
    private Button chooseDirBtn;
    
    @FXML
    private ComboBox<String> speedComboBox;

    private static final java.util.List<String> QUALITY_OPTIONS = java.util.List.of("Best", "1080p", "720p", "480p", "360p");

    private final DownloadManager manager = DownloadManager.getInstance();
    private final SettingsManager settingsManager = new SettingsManager();
    private SettingsManager.Settings settings;

    @FXML
    public void initialize() {
        // Init download dir from settings
        settings = settingsManager.load();
        java.nio.file.Path dir = (settings.downloadDir != null && !settings.downloadDir.isBlank())
                ? java.nio.file.Paths.get(settings.downloadDir)
                : java.nio.file.Paths.get(System.getProperty("user.home"), "Downloads");
        if (downloadDirLabel != null) downloadDirLabel.setText(dir.toString());
        manager.setDownloadDir(dir);
        manager.setDefaultQuality(settings.downloadQuality != null && !settings.downloadQuality.isBlank()
                ? settings.downloadQuality
                : "720p");

        downloadsTable.setItems(manager.getItems());
        
        // Add listener to refresh table when items change
        manager.getItems().addListener((javafx.collections.ListChangeListener<DownloadItem>) c -> {
            downloadsTable.refresh();
        });

        titleColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().getTitle()));

        qualityColumn.setCellValueFactory(cd -> cd.getValue().qualityProperty());
        qualityColumn.setCellFactory(col -> new TableCell<>() {
            private final ComboBox<String> combo = new ComboBox<>();
            {
                combo.getItems().addAll(QUALITY_OPTIONS);
                combo.setMaxWidth(Double.MAX_VALUE);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                DownloadItem di = getTableView().getItems().get(getIndex());
                combo.setOnAction(null);
                String current = di.getQuality();
                combo.getSelectionModel().select(current != null ? current : manager.getDefaultQuality());
                combo.setOnAction(e -> {
                    String selected = combo.getSelectionModel().getSelectedItem();
                    if (selected != null) {
                        di.setQuality(selected);
                        settings.downloadQuality = selected;
                        try { settingsManager.save(settings); } catch (Exception ignore) {}
                    }
                });
                setGraphic(combo);
            }
        });

        seasonColumn.setCellFactory(col -> new TableCell<>() {
            private final ComboBox<String> combo = new ComboBox<>();
            {
                combo.setMaxWidth(Double.MAX_VALUE);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
            @Override
            protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                DownloadItem di = getTableView().getItems().get(getIndex());
                ensureEpisodesLoaded(di);
                List<String> seasons = di.getSeasons();
                combo.getItems().setAll(seasons);
                // Only restore previously selected season (no auto-select)
                String selected = di.getSelectedSeason();
                if (selected != null && !selected.isEmpty() && seasons.contains(selected)) {
                    combo.getSelectionModel().select(selected);
                } else {
                    combo.getSelectionModel().clearSelection();
                    combo.setPromptText("Seleziona...");
                }
                combo.setOnAction(e -> {
                    di.setSelectedSeason(combo.getSelectionModel().getSelectedItem());
                    downloadsTable.refresh();
                });
                setGraphic(combo);
            }
        });
        seasonColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().getSelectedSeason()));

        episodesColumn.setCellFactory(col -> new TableCell<>() {
            private final CheckComboBox<Episode> checkCombo = new CheckComboBox<>();
            {
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                checkCombo.setMaxWidth(Double.MAX_VALUE);
            }
            @Override
            protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                DownloadItem di = getTableView().getItems().get(getIndex());
                ensureEpisodesLoaded(di);
                List<Episode> list = di.getEpisodesForSeason(di.getSelectedSeason());
                ObservableList<Episode> obs = FXCollections.observableArrayList(list);
                checkCombo.getItems().setAll(obs);
                checkCombo.getCheckModel().clearChecks();
                // Restore only previously selected episodes (no auto-select)
                for (Episode ep : di.getSelectedEpisodes()) {
                    int idx = obs.indexOf(ep);
                    if (idx >= 0) checkCombo.getCheckModel().check(idx);
                }
                // Bind check changes directly to the model
                checkCombo.getCheckModel().getCheckedItems().addListener((ListChangeListener<Episode>) c -> {
                    List<Episode> checked = new ArrayList<>(checkCombo.getCheckModel().getCheckedItems());
                    checked.removeIf(ep -> ep == null);
                    di.getSelectedEpisodes().setAll(checked);
                });
                setGraphic(checkCombo);
            }
        });
        episodesColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(diEpisodesSummary(cd.getValue())));

        statusColumn.setCellValueFactory(cd -> cd.getValue().statusProperty().asString());

        speedColumn.setCellValueFactory(cd -> cd.getValue().speedProperty());
        speedColumn.setCellFactory(col -> new TableCell<>() {
            private final Label lbl = new Label();
            {
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                MenuItem copyItem = new MenuItem("Copia");
                ContextMenu cm = new ContextMenu(copyItem);
                copyItem.setOnAction(e -> {
                    String text = lbl.getText();
                    if (text != null) {
                        ClipboardContent content = new ClipboardContent();
                        content.putString(text);
                        Clipboard.getSystemClipboard().setContent(content);
                    }
                });
                lbl.setContextMenu(cm);
                lbl.setWrapText(true);
                lbl.setMaxWidth(Double.MAX_VALUE);
            }
            @Override
            protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    lbl.setText(s);
                    setGraphic(lbl);
                }
            }
        });

        progressColumn.setCellValueFactory(cd -> cd.getValue().progressProperty().asObject());
        progressColumn.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);
            {
                bar.setMaxWidth(Double.MAX_VALUE);
            }
            @Override
            protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    DownloadItem di = getTableView().getItems().get(getIndex());
                    double v = di.getProgress();
                    if (v < 0) {
                        bar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                    } else {
                        bar.setProgress(v);
                    }
                    setGraphic(bar);
                }
            }
        });

        actionsColumn.setCellFactory(col -> new TableCell<>() {
            private final Button startBtn = new Button("Start");
            private final Button cancelBtn = new Button("Annulla");
            private final HBox box = new HBox(6, startBtn, cancelBtn);
            {
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                box.setPadding(new Insets(2));
                startBtn.setOnAction(e -> {
                    DownloadItem di = getTableView().getItems().get(getIndex());
                    manager.start(di);
                });
                cancelBtn.setOnAction(e -> {
                    DownloadItem di = getTableView().getItems().get(getIndex());
                    manager.cancel(di);
                });
            }
            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                DownloadItem di = getTableView().getItems().get(getIndex());
                DownloadStatus status = di.getStatus();
                
                // Enable/disable buttons based on status
                startBtn.setDisable(status != DownloadStatus.PENDING);
                cancelBtn.setDisable(status != DownloadStatus.RUNNING && status != DownloadStatus.PENDING);
                
                setGraphic(box);
            }
        });

        startSelectedBtn.setOnAction(e -> manager.startSelected(downloadsTable.getSelectionModel().getSelectedItems()));
        cancelSelectedBtn.setOnAction(e -> manager.cancelSelected(downloadsTable.getSelectionModel().getSelectedItems()));
        removeCompletedBtn.setOnAction(e -> manager.removeCompleted());

        downloadsTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        if (chooseDirBtn != null) {
            chooseDirBtn.setOnAction(e -> onChooseDir());
        }
        
        // Init speed combo
        if (speedComboBox != null) {
            speedComboBox.getItems().addAll("x1 (Sicuro)", "x2 (Bilanciato)", "x4 (Veloce)", "x8 (Massimo)");
            int speed = settings.downloadSpeed;
            int idx = speed == 1 ? 0 : speed == 2 ? 1 : speed == 4 ? 2 : 3;
            speedComboBox.getSelectionModel().select(idx);
            manager.setDownloadSpeed(speed);
            
            speedComboBox.setOnAction(e -> {
                int selectedIdx = speedComboBox.getSelectionModel().getSelectedIndex();
                int newSpeed = selectedIdx == 0 ? 1 : selectedIdx == 1 ? 2 : selectedIdx == 2 ? 4 : 8;
                manager.setDownloadSpeed(newSpeed);
                settings.downloadSpeed = newSpeed;
                try { settingsManager.save(settings); } catch (Exception ignore) {}
            });
        }
        
    }

    private String diEpisodesSummary(DownloadItem di) {
        List<Episode> list = di.getSelectedEpisodes();
        if (list == null || list.isEmpty()) return "";
        return list.stream().map(Episode::toString).collect(Collectors.joining(", "));
    }

    private void ensureEpisodesLoaded(DownloadItem di) {
        if (di == null) return;
        boolean needsLoad = di.getAllEpisodes().isEmpty() || di.getAllEpisodes().stream().anyMatch(ep -> ep.getId() == null || ep.getId().isBlank());
        if (needsLoad) {
            var provider = di.getProvider();
            var item = di.getItem();
            new Thread(() -> {
                try {
                    List<Episode> eps = provider.listEpisodes(item);
                    if (eps != null && !eps.isEmpty()) {
                        javafx.application.Platform.runLater(() -> {
                            di.setEpisodes(eps);
                            downloadsTable.refresh();
                        });
                    }
                } catch (Exception e) {
                    System.err.println("Failed to load episodes for " + item.getTitle() + ": " + e.getMessage());
                }
            }, "load-episodes-" + di.getId()).start();
        }
    }

    private void onChooseDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        try {
            File init = new File(downloadDirLabel.getText());
            if (init.isDirectory()) chooser.setInitialDirectory(init);
        } catch (Exception ignore) {}
        chooser.setTitle("Scegli cartella download");
        File sel = chooser.showDialog(chooseDirBtn.getScene().getWindow());
        if (sel != null) {
            java.nio.file.Path p = sel.toPath();
            downloadDirLabel.setText(p.toString());
            manager.setDownloadDir(p);
            settings.downloadDir = p.toString();
            try { settingsManager.save(settings); } catch (Exception ignore) {}
        }
    }
}
