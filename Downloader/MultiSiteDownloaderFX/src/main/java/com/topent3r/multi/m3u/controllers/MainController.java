package com.topent3r.multi.m3u.controllers;

import com.topent3r.multi.m3u.models.Channel;
import com.topent3r.multi.m3u.services.M3UParser;
import com.topent3r.multi.m3u.utils.SettingsManager;
import com.topent3r.multi.m3u.utils.SettingsManager.Settings;
import com.topent3r.multi.model.MediaItem;
import com.topent3r.multi.model.Episode;
import com.topent3r.multi.services.M3UProvider;
import com.topent3r.multi.download.DownloadManager;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MainController {

    @FXML private TextField urlField, searchField;
    @FXML private Button loadBtn, exportBtn, downloadSelectedBtn;
    @FXML private Label statusLabel;
    @FXML private ToggleButton hoverSelectToggle;

    @FXML private Tab tabAllTab, tabLiveTab, tabVodTab;
    @FXML private TableView<Channel> tableAll, tableLive, tableVod;

    private final ObservableList<Channel> allData  = FXCollections.observableArrayList();
    private final ObservableList<Channel> liveData = FXCollections.observableArrayList();
    private final ObservableList<Channel> vodData  = FXCollections.observableArrayList();

    // Wrap per filtro + sort (freccia ↑↓ visibile e funzionante)
    private FilteredList<Channel> allFiltered, liveFiltered, vodFiltered;
    private SortedList<Channel>   allSorted,   liveSorted,   vodSorted;

    private final M3UParser parser = new M3UParser();
    private final SettingsManager settingsManager = new SettingsManager();
    private Settings settings;

    private M3UProvider provider;
    private String lastPlaylistUrl = "";

    // colonna ID del tab VOD per sort di default
    private TableColumn<Channel, String> idColVod;

    @FXML
    public void initialize() {
        settings = settingsManager.load();

        setupTable(tableAll);
        setupTable(tableLive);
        idColVod = setupTable(tableVod); // salva ref per sort default VOD

        // --- Filtered + Sorted (mostra frecce e mantiene sort con filtro) ---
        allFiltered  = new FilteredList<>(allData, c -> true);
        liveFiltered = new FilteredList<>(liveData, c -> true);
        vodFiltered  = new FilteredList<>(vodData, c -> true);

        allSorted  = new SortedList<>(allFiltered);
        liveSorted = new SortedList<>(liveFiltered);
        vodSorted  = new SortedList<>(vodFiltered);

        allSorted.comparatorProperty().bind(tableAll.comparatorProperty());
        liveSorted.comparatorProperty().bind(tableLive.comparatorProperty());
        vodSorted.comparatorProperty().bind(tableVod.comparatorProperty());

        tableAll.setItems(allSorted);
        tableLive.setItems(liveSorted);
        tableVod.setItems(vodSorted);
        // ---------------------------------------------------------------------

        // sort default sul tab VOD: ID ↓
        if (idColVod != null) {
            idColVod.setSortType(TableColumn.SortType.DESCENDING);
            tableVod.getSortOrder().setAll(idColVod);
            tableVod.sort();
        }

        urlField.setText(settings.lastUrl == null ? "" : settings.lastUrl);
        lastPlaylistUrl = urlField.getText() == null ? "" : urlField.getText().trim();

        exportBtn.disableProperty().bind(Bindings.isEmpty(allData));

        wireQuickDragSelection(tableAll);
        wireQuickDragSelection(tableLive);
        wireQuickDragSelection(tableVod);

        status("Pronto.");
    }

    /** Crea colonne: Sel | ID (sx) | Nome | Gruppo | Logo | URL. */
    private TableColumn<Channel, String> setupTable(TableView<Channel> table) {
        // Sel
        TableColumn<Channel, Boolean> colSel = new TableColumn<>("Sel");
        colSel.setPrefWidth(48);
        colSel.setResizable(false);
        colSel.setSortable(false);
        colSel.setCellFactory(col -> new TableCell<>() {
            private final CheckBox chk = new CheckBox();
            { setContentDisplay(ContentDisplay.GRAPHIC_ONLY); setGraphic(chk); }
            @Override protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0) { setGraphic(null); return; }
                setGraphic(chk);
                TableView<Channel> tv = getTableView();
                int idx = getIndex();
                chk.setSelected(tv.getSelectionModel().isSelected(idx));
                chk.setOnAction(e -> {
                    if (chk.isSelected()) tv.getSelectionModel().select(idx);
                    else tv.getSelectionModel().clearSelection(idx);
                });
            }
        });

        // ID (allineato a sinistra, comparatore numerico)
        TableColumn<Channel, String> cId = new TableColumn<>("ID");
        cId.setCellValueFactory(new PropertyValueFactory<>("id"));
        cId.setPrefWidth(90);
        cId.setStyle("-fx-alignment: CENTER-LEFT;"); // ⬅️ CENTER-LEFT
        cId.setSortable(true);
        cId.setComparator((a,b) -> {
            boolean da = a != null && a.matches("\\d+");
            boolean db = b != null && b.matches("\\d+");
            if (da && db) return Long.compare(Long.parseLong(a), Long.parseLong(b));
            if (da) return -1;
            if (db) return 1;
            return String.CASE_INSENSITIVE_ORDER.compare(a == null ? "" : a, b == null ? "" : b);
        });

        // Nome (A–Z/Z–A case-insensitive)
        TableColumn<Channel, String> cName = new TableColumn<>("Nome");
        cName.setCellValueFactory(new PropertyValueFactory<>("name"));
        cName.setPrefWidth(320);
        cName.setSortable(true);
        cName.setComparator((a,b) ->
                String.CASE_INSENSITIVE_ORDER.compare(a == null ? "" : a, b == null ? "" : b));

        // Gruppo
        TableColumn<Channel, String> cGroup = new TableColumn<>("Gruppo");
        cGroup.setCellValueFactory(new PropertyValueFactory<>("group"));
        cGroup.setPrefWidth(240);
        cGroup.setSortable(true);
        cGroup.setComparator((a,b) ->
                String.CASE_INSENSITIVE_ORDER.compare(a == null ? "" : a, b == null ? "" : b));

        // Logo (immagine 24x24; no sort)
        TableColumn<Channel, String> cLogo = new TableColumn<>("Logo");
        cLogo.setCellValueFactory(new PropertyValueFactory<>("logo"));
        cLogo.setPrefWidth(64);
        cLogo.setSortable(false);
        cLogo.setCellFactory(col -> new TableCell<>() {
            private final ImageView iv = new ImageView();
            { iv.setFitWidth(24); iv.setFitHeight(24); iv.setPreserveRatio(true); }
            @Override protected void updateItem(String url, boolean empty) {
                super.updateItem(url, empty);
                if (empty || url == null || url.isBlank()) {
                    setGraphic(null);
                } else {
                    try {
                        iv.setImage(new Image(url, true));
                        setGraphic(iv);
                    } catch (Exception e) {
                        setGraphic(null);
                    }
                }
            }
        });

        // URL
        TableColumn<Channel, String> cUrl = new TableColumn<>("URL");
        cUrl.setCellValueFactory(new PropertyValueFactory<>("url"));
        cUrl.setPrefWidth(520);
        cUrl.setSortable(true);

        // Applica colonne
        table.getColumns().setAll(colSel, cId, cName, cGroup, cLogo, cUrl);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("Nessun elemento"));
        // ❌ NON usare setSortPolicy(...), il sort default è già attivo.

        // Menu rapido (tasto destro) per ordinamenti
        MenuItem miIdAsc    = new MenuItem("Ordina per ID ↑");
        MenuItem miIdDesc   = new MenuItem("Ordina per ID ↓");
        MenuItem miNameAsc  = new MenuItem("Ordina per Nome A–Z");
        MenuItem miNameDesc = new MenuItem("Ordina per Nome Z–A");

        miIdAsc.setOnAction(e -> { cId.setSortType(TableColumn.SortType.ASCENDING);    table.getSortOrder().setAll(cId);   table.sort(); });
        miIdDesc.setOnAction(e -> { cId.setSortType(TableColumn.SortType.DESCENDING);   table.getSortOrder().setAll(cId);   table.sort(); });
        miNameAsc.setOnAction(e -> { cName.setSortType(TableColumn.SortType.ASCENDING); table.getSortOrder().setAll(cName); table.sort(); });
        miNameDesc.setOnAction(e -> { cName.setSortType(TableColumn.SortType.DESCENDING);table.getSortOrder().setAll(cName); table.sort(); });

        table.setContextMenu(new ContextMenu(miIdAsc, miIdDesc, new SeparatorMenuItem(), miNameAsc, miNameDesc));

        return cId;
    }

    private void wireQuickDragSelection(TableView<Channel> tv) {
        tv.setRowFactory(t -> {
            TableRow<Channel> row = new TableRow<>();

            row.setOnDragDetected(e -> {
                if (!row.isEmpty() && e.isPrimaryButtonDown()) { row.startFullDrag(); e.consume(); }
            });

            row.setOnMousePressed(e -> {
                if (!isQuickSelectOn()) return;
                if (row.isEmpty() || !e.isPrimaryButtonDown()) return;
                tv.getSelectionModel().clearSelection();
                tv.getSelectionModel().select(row.getIndex());
                e.consume();
            });

            row.setOnMouseDragEntered(e -> {
                if (!isQuickSelectOn()) return;
                if (row.isEmpty() || !e.isPrimaryButtonDown()) return;
                tv.getSelectionModel().select(row.getIndex());
                e.consume();
            });

            row.setOnMouseEntered(e -> {
                if (!isQuickSelectOn()) return;
                if (row.isEmpty()) return;
                if (e.isPrimaryButtonDown()) tv.getSelectionModel().select(row.getIndex());
            });

            return row;
        });
    }
    private boolean isQuickSelectOn() { return hoverSelectToggle != null && hoverSelectToggle.isSelected(); }

    @FXML
    private void onLoad(ActionEvent e) {
        String url = urlField.getText() == null ? "" : urlField.getText().trim();
        if (url.isEmpty()) { status("Inserisci un URL M3U valido."); return; }

        lastPlaylistUrl = url;
        settings.lastUrl = url;
        try { settingsManager.save(settings); } catch (IOException ignore) {}

        allData.clear(); liveData.clear(); vodData.clear();
        status("Scarico playlist...");

        Task<List<Channel>> task = new Task<>() {
            @Override protected List<Channel> call() throws Exception {
                String content = parser.download(url);
                return parser.parse(content);
            }
        };

        task.setOnSucceeded(ev -> {
            List<Channel> list = task.getValue();
            allData.addAll(list);
            liveData.addAll(list.stream().filter(ch -> !ch.isVod()).collect(Collectors.toList()));
            vodData.addAll(list.stream().filter(Channel::isVod).collect(Collectors.toList()));
            applySearchFilter();

            // sort default VOD: ID ↓
            if (idColVod != null) {
                idColVod.setSortType(TableColumn.SortType.DESCENDING);
                tableVod.getSortOrder().setAll(idColVod);
                tableVod.sort();
            }

            status("Caricati " + list.size() + " elementi.");
        });

        task.setOnFailed(ev -> status("Errore: " + (task.getException()!=null ? task.getException().getMessage() : "sconosciuto")));
        new Thread(task, "loader").start();
    }

    @FXML
    private void onExport(ActionEvent e) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Esporta CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        chooser.setInitialFileName("topent3r_export.csv");
        File file = chooser.showSaveDialog(exportBtn.getScene().getWindow());
        if (file == null) return;

        try (FileWriter fw = new FileWriter(file)) {
            fw.write("id,name,group,url,tvgId,logo,vod\n");
            for (Channel ch : currentTable().getItems()) {
                fw.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%s\n",
                        esc(ch.getId()), esc(ch.getName()), esc(ch.getGroup()), esc(ch.getUrl()),
                        esc(ch.getTvgId()), esc(ch.getLogo()), ch.isVod()));
            }
            status("Esportato " + currentTable().getItems().size() + " elementi.");
        } catch (IOException ex) {
            status("Errore esportazione: " + ex.getMessage());
        }
    }

    @FXML private void onSearchChanged() { applySearchFilter(); }

    private void applySearchFilter() {
        String q = (searchField.getText()==null ? "" : searchField.getText()).toLowerCase().trim();
        Predicate<Channel> pred = ch ->
                safe(ch.getId()).contains(q)   ||
                safe(ch.getName()).contains(q) ||
                safe(ch.getGroup()).contains(q)||
                safe(ch.getUrl()).contains(q);

        if (allFiltered  != null) allFiltered.setPredicate(pred);
        if (liveFiltered != null) liveFiltered.setPredicate(pred);
        if (vodFiltered  != null) vodFiltered.setPredicate(pred);
    }

    private static String safe(String s){ return s==null ? "" : s.toLowerCase(); }

    private TableView<Channel> currentTable() {
        if (tabLiveTab.isSelected()) return tableLive;
        if (tabVodTab.isSelected())  return tableVod;
        return tableAll;
    }

    @FXML
    private void onDownloadSelected() {
        List<Channel> selected = getSelectedOrAllCurrent();
        if (selected.isEmpty()) {
            status("Seleziona almeno un canale da scaricare.");
            return;
        }
        
        // Crea provider se non esiste
        if (provider == null) {
            provider = new M3UProvider(lastPlaylistUrl);
        }
        
        downloadSelectedBtn.setDisable(true);
        status("Aggiunta " + selected.size() + " canali al tab Downloads...");
        
        // Aggiungi ogni canale come MediaItem al DownloadManager
        new Thread(() -> {
            try {
                for (Channel channel : selected) {
                    // Registra il canale nel provider
                    provider.registerChannel(channel);
                    
                    // Crea MediaItem per il canale
                    MediaItem item = new MediaItem(
                        channel.getId(),
                        channel.getName(),
                        channel.isVod() ? "movie" : "live",
                        "M3U",
                        channel.getGroup(),
                        null  // year
                    );
                    
                    // Crea singolo "episode" per il canale
                    Episode ep = new Episode(channel.getId(), "1", "1", channel.getName());
                    
                    List<Episode> episodes = Collections.singletonList(ep);
                    
                    // Aggiungi al DownloadManager
                    Platform.runLater(() -> {
                        DownloadManager.getInstance().enqueue(item, provider, episodes);
                    });
                }
                
                Platform.runLater(() -> {
                    downloadSelectedBtn.setDisable(false);
                    status(selected.size() + " canali aggiunti al tab Downloads.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    downloadSelectedBtn.setDisable(false);
                    status("Errore: " + ex.getMessage());
                });
            }
        }, "m3u-add-downloads").start();
    }

    private List<Channel> getSelectedOrAllCurrent() {
        TableView<Channel> tv = currentTable();
        var sel = tv.getSelectionModel().getSelectedItems();
        if (sel != null && !sel.isEmpty()) return new ArrayList<>(sel);
        return new ArrayList<>(tv.getItems());
    }

    private static String buildNiceName(Channel ch) {
        String name = ch.getName() != null ? ch.getName() : "stream";
        Matcher m = Pattern.compile("(?i)S(\\d{1,2})\\s*E(\\d{1,2})").matcher(name);
        if (m.find()) {
            String base = name.replaceAll("(?i)S\\d{1,2}\\s*E\\d{1,2}", "").trim();
            return sanitize(base) + " " + String.format("%02dx%02d",
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        Matcher y = Pattern.compile("\\((\\d{4})\\)").matcher(name);
        if (y.find()) {
            String base = name.replaceAll("\\(\\d{4}\\)", "").trim();
            return sanitize(base) + " (" + y.group(1) + ")";
        }
        return sanitize(name);
    }

    private static String guessExt(String url) {
        if (url == null) return ".bin";
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(".m3u8")) return ".mp4";
        if (u.endsWith(".mp4"))  return ".mp4";
        if (u.endsWith(".ts"))   return ".ts";
        return ".mp4";
    }

    private static Map<String,String> browserHeaders(String contentUrl, String playlistUrl){
        String ref  = refererFor(playlistUrl);
        String host = hostOf(contentUrl);

        Map<String,String> h = new LinkedHashMap<>();
        h.put("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127 Safari/537.36");
        h.put("Accept","*/*");
        h.put("Accept-Language","it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7");
        h.put("Connection","keep-alive");
        if (!ref.isBlank()) {
            h.put("Referer", ref);
            h.put("Origin",  ref);
        }
        if (!host.isBlank()) {
            h.put("Host", host);
        }
        h.put("Range","bytes=0-");
        return h;
    }
    private static String refererFor(String url) {
        try {
            if (url == null || url.isBlank()) return "";
            var u = new java.net.URI(url);
            return u.getScheme() + "://" + u.getHost() + "/";
        } catch (Exception e) { return ""; }
    }
    private static String hostOf(String url){
        try { return new java.net.URI(url).getHost(); } catch(Exception e){ return ""; }
    }

    private static String sanitize(String s) { return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim(); }
    private static String esc(String s) { return s == null ? "" : s.replace("\"", "\"\""); }
    private void status(String s) { if (statusLabel != null) Platform.runLater(() -> statusLabel.setText(s)); }
}
