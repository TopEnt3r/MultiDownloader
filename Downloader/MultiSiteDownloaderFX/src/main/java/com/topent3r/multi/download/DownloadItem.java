package com.topent3r.multi.download;

import com.topent3r.multi.model.Episode;
import com.topent3r.multi.model.MediaItem;
import com.topent3r.multi.services.ContentProvider;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.*;
import java.util.stream.Collectors;

public class DownloadItem {
    private final String id = UUID.randomUUID().toString();
    private final MediaItem item;
    private final ContentProvider provider;

    private final StringProperty title = new SimpleStringProperty("");
    private final StringProperty site = new SimpleStringProperty("");
    private final StringProperty quality = new SimpleStringProperty("720p");
    private final StringProperty selectedSeason = new SimpleStringProperty("");
    private final ObjectProperty<DownloadStatus> status = new SimpleObjectProperty<>(DownloadStatus.PENDING);
    private final DoubleProperty progress = new SimpleDoubleProperty(0.0);
    private final StringProperty speed = new SimpleStringProperty("");

    private final ObservableList<Episode> allEpisodes = FXCollections.observableArrayList();
    private final ObservableList<Episode> selectedEpisodes = FXCollections.observableArrayList();

    public DownloadItem(MediaItem item, ContentProvider provider) {
        this.item = item;
        this.provider = provider;
        this.title.set(item.getTitle());
        this.site.set(item.getSource());
    }

    public String getId() { return id; }
    public MediaItem getItem() { return item; }
    public ContentProvider getProvider() { return provider; }

    public String getTitle() { return title.get(); }
    public StringProperty titleProperty() { return title; }

    public String getSite() { return site.get(); }
    public StringProperty siteProperty() { return site; }

    public String getQuality() { return quality.get(); }
    public void setQuality(String q) { quality.set(q); }
    public StringProperty qualityProperty() { return quality; }

    public String getSelectedSeason() { return selectedSeason.get(); }
    public StringProperty selectedSeasonProperty() { return selectedSeason; }
    public void setSelectedSeason(String s) { selectedSeason.set(s); }

    public DownloadStatus getStatus() { return status.get(); }
    public ObjectProperty<DownloadStatus> statusProperty() { return status; }
    public void setStatus(DownloadStatus st) { status.set(st); }

    public double getProgress() { return progress.get(); }
    public DoubleProperty progressProperty() { return progress; }
    public void setProgress(double v) { progress.set(v); }

    public String getSpeed() { return speed.get(); }
    public StringProperty speedProperty() { return speed; }
    public void setSpeed(String s) { speed.set(s); }

    public ObservableList<Episode> getAllEpisodes() { return allEpisodes; }
    public ObservableList<Episode> getSelectedEpisodes() { return selectedEpisodes; }

    public void setEpisodes(List<Episode> list) {
        allEpisodes.setAll(list);
        List<String> seasons = getSeasons();
        if (!seasons.isEmpty()) {
            selectedSeason.set(seasons.get(0));
        }
    }

    public List<String> getSeasons() {
        return allEpisodes.stream()
                .map(Episode::getSeason)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparingInt(s -> safeParseInt(s)))
                .collect(Collectors.toList());
    }

    public List<Episode> getEpisodesForSeason(String season) {
        if (season == null) return Collections.emptyList();
        return allEpisodes.stream()
                .filter(e -> season.equals(e.getSeason()))
                .sorted(Comparator.comparingInt(e -> safeParseInt(e.getEpisode())))
                .collect(Collectors.toList());
    }

    private int safeParseInt(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "0")); } catch (Exception ignore) { return 0; }
    }
}
