package com.topent3r.multi.download;

import com.topent3r.multi.model.Episode;
import com.topent3r.multi.model.MediaItem;
import com.topent3r.multi.services.ContentProvider;
import com.topent3r.multi.services.ContentProvider.DownloadCallback;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DownloadManager {
    private static final DownloadManager INSTANCE = new DownloadManager();

    public static DownloadManager getInstance() { return INSTANCE; }

    private final ObservableList<DownloadItem> items = FXCollections.observableArrayList();
    private final Set<String> runningSites = ConcurrentHashMap.newKeySet();
    private volatile java.nio.file.Path downloadDir = java.nio.file.Paths.get(System.getProperty("user.home"), "Downloads");
    private volatile int downloadSpeed = 2; // 1=x1, 2=x2, 4=x4, 8=x8
    private volatile String defaultQuality = "720p";

    private DownloadManager() {}

    public ObservableList<DownloadItem> getItems() { return items; }

    public void setDownloadDir(java.nio.file.Path dir) {
        if (dir != null) this.downloadDir = dir;
    }

    private void updateConfigQuality(String quality) {
        if (quality == null || quality.isBlank()) return;
        try {
            java.nio.file.Path configPath = java.nio.file.Paths.get(
                    System.getProperty("user.dir")).getParent().getParent()
                    .resolve("Downloader/StreamingCommunity/StreamingCommunity-main/config.json");
            if (!java.nio.file.Files.exists(configPath)) return;
            String content = java.nio.file.Files.readString(configPath);
            content = content.replaceAll("\\\"force_resolution\\\":\\s*\\\"[^\\\"]*\\\"",
                    "\\\"force_resolution\\\": \\\"" + quality + "\\\"");
            java.nio.file.Files.writeString(configPath, content);
        } catch (Exception e) {
            System.err.println("Failed to set quality " + quality + ": " + e.getMessage());
        }
    }
    public java.nio.file.Path getDownloadDir() { return this.downloadDir; }
    
    public void setDownloadSpeed(int speed) {
        if (speed == 1 || speed == 2 || speed == 4 || speed == 8) {
            this.downloadSpeed = speed;
        }
    }
    public int getDownloadSpeed() { return this.downloadSpeed; }
    public void setDefaultQuality(String quality) {
        if (quality != null && !quality.isBlank()) {
            this.defaultQuality = quality;
        }
    }
    public String getDefaultQuality() { return this.defaultQuality; }

    public void enqueue(MediaItem item, ContentProvider provider, List<Episode> allEpisodes) {
        enqueue(item, provider, allEpisodes, null);
    }

    public void enqueue(MediaItem item, ContentProvider provider, List<Episode> allEpisodes, String quality) {
        DownloadItem di = new DownloadItem(item, provider);
        di.setQuality(quality != null && !quality.isBlank() ? quality : defaultQuality);
        if (allEpisodes != null) di.setEpisodes(allEpisodes);
        Platform.runLater(() -> items.add(di));
    }

    public void startSelected(Collection<DownloadItem> selected) {
        if (selected == null) return;
        for (DownloadItem di : selected) start(di);
    }

    public void start(DownloadItem di) {
        if (di == null) return;
        if (di.getStatus() != DownloadStatus.PENDING) return;
        String site = di.getItem().getSourceAlias();
        synchronized (runningSites) {
            if (runningSites.contains(site)) return;
            runningSites.add(site);
        }
        di.setStatus(DownloadStatus.RUNNING);
        di.setSpeed("...");
        di.setProgress(-1);
        new Thread(() -> runDownload(di, site), "download-" + site + "-" + di.getId()).start();
    }

    private void runDownload(DownloadItem di, String site) {
        boolean success = true;
        java.util.List<Path> downloadedFiles = new java.util.ArrayList<>();
        String providerName = di.getProvider().getDisplayName(); // Declare at method level
        try {
            Path out = this.downloadDir;
            ContentProvider provider = di.getProvider();
            MediaItem item = di.getItem();
            var eps = di.getSelectedEpisodes();
            
            System.err.println("=== DownloadManager.runDownload called for: " + di.getTitle());
            
            String type = item.getType() == null ? "" : item.getType().toLowerCase();
            boolean isMovie = type.contains("film") || type.contains("movie");
            
            // For movies, create a dummy episode
            if (isMovie && (eps == null || eps.isEmpty())) {
                System.err.println("=== Detected MOVIE - creating dummy episode");
                Episode dummyEp = new Episode(item.getId(), "1", "1", item.getTitle());
                di.getSelectedEpisodes().setAll(java.util.Collections.singletonList(dummyEp));
                eps = di.getSelectedEpisodes();
            }
            
            // Filter out null episodes (bug in CheckComboBox)
            if (eps != null) {
                eps.removeIf(e -> e == null);
            }
            
            System.err.println("=== Selected episodes count: " + (eps == null ? "null" : eps.size()));
            if (eps != null) {
                for (Episode ep : eps) {
                    if (ep != null) {
                        System.err.println("===   - " + ep + " (season=" + ep.getSeason() + ", episode=" + ep.getEpisode() + ", id=" + ep.getId() + ")");
                    }
                }
            }
            
            // Auto-seleziona il primo episodio della stagione corrente se nessuno è selezionato (solo per serie TV)
            if ((eps == null || eps.isEmpty()) && type.contains("tv")) {
                List<Episode> available = di.getEpisodesForSeason(di.getSelectedSeason());
                if (available != null && !available.isEmpty()) {
                    Episode first = available.get(0);
                    di.getSelectedEpisodes().add(first);
                    eps = di.getSelectedEpisodes();
                    System.err.println("=== Auto-selected first episode: " + first);
                }
            }
            
            if (eps != null && !eps.isEmpty()) {
                int total = eps.size();
                int[] done = {0};
                for (Episode ep : eps) {
                    try {
                        System.err.println("=== Starting download for episode: " + ep);

                        if ("StreamingCommunity".equals(providerName)) {
                            updateConfigQuality(di.getQuality());
                        }
                        
                        // Use callback version to get progress updates
                        provider.download(item, ep, out, new ContentProvider.DownloadCallback() {
                            @Override
                            public void onProgress(String message) {
                                Platform.runLater(() -> di.setSpeed(message));
                            }
                            @Override
                            public void onSpeed(String speed) {
                                Platform.runLater(() -> di.setSpeed("⚡ " + speed));
                            }
                        });
                        
                        System.err.println("=== Download completed for episode: " + ep);
                        
                        // Track expected file path for verification
                        String fileName;
                        if (isMovie) {
                            fileName = com.topent3r.multi.utils.FileNameFormatter.formatMovie(item.getTitle(), item.getYear());
                        } else {
                            // RaiPlay uses format: 01x01 - Episode Title.mp4
                            if ("RaiPlay".equals(providerName)) {
                                String seasonStr = String.format("%02d", Integer.parseInt(ep.getSeason()));
                                String episodeStr = String.format("%02d", Integer.parseInt(ep.getEpisode()));
                                fileName = seasonStr + "x" + episodeStr + " - " + ep.getTitle() + ".mp4";
                            } else {
                                fileName = com.topent3r.multi.utils.FileNameFormatter.formatEpisode(item.getTitle(), ep.getSeason(), ep.getEpisode(), ep.getTitle());
                            }
                        }
                        Path expectedFile = out.resolve(fileName);
                        downloadedFiles.add(expectedFile);
                        
                        done[0]++;
                        double prog = (double) done[0] / total;
                        double val = prog >= 1.0 ? 1.0 : Math.max(0.0, Math.min(1.0, prog));
                        double finalVal = val;
                        Platform.runLater(() -> {
                            di.setProgress(finalVal);
                            di.setSpeed("");  // Clear speed on completion
                        });
                    } catch (Exception ex) {
                        success = false;
                        String msg = ex.getMessage();
                        System.err.println("=== Download FAILED for episode " + ep + ": " + msg);
                        ex.printStackTrace();
                        Platform.runLater(() -> di.setSpeed(msg == null ? "Errore" : ("Errore: " + msg)));
                        break;
                    }
                }
            } else {
                System.err.println("=== ERROR: No episodes selected after auto-selection attempt!");
                success = false;
                Platform.runLater(() -> di.setSpeed("Errore: Nessun episodio selezionato"));
            }
        } finally {
            synchronized (runningSites) {
                runningSites.remove(site);
            }
            
            // Verify downloaded files actually exist
            boolean filesExist = true;
            
            // Skip verification for RaiPlay/MediasetInfinity - they manage their own folder structure and filenames
            boolean skipVerification = "RaiPlay".equals(providerName) || "MediasetInfinity".equals(providerName);

            if (skipVerification) {
                System.err.println("=== Skipping file verification for " + providerName + " (uses custom folder structure)");
            } else if (success && !downloadedFiles.isEmpty()) {
                for (Path file : downloadedFiles) {
                    if (!java.nio.file.Files.exists(file)) {
                        System.err.println("=== VERIFICATION FAILED: File not found: " + file);
                        filesExist = false;
                    } else {
                        try {
                            long size = java.nio.file.Files.size(file);
                            System.err.println("=== VERIFICATION OK: " + file + " (size=" + size + " bytes)");
                            if (size == 0) {
                                System.err.println("=== VERIFICATION FAILED: File is empty: " + file);
                                filesExist = false;
                            }
                        } catch (Exception e) {
                            System.err.println("=== VERIFICATION ERROR: " + e.getMessage());
                            filesExist = false;
                        }
                    }
                }
            }
            
            boolean ok = success && filesExist;
            final boolean finalFilesExist = filesExist;
            Platform.runLater(() -> {
                di.setStatus(ok ? DownloadStatus.COMPLETED : DownloadStatus.FAILED);
                if (ok) {
                    di.setSpeed(""); // Clear speed on success
                } else if (!finalFilesExist) {
                    di.setSpeed("Errore: File non trovato o vuoto");
                }
                // su errore lascia il messaggio per debug
            });
            // trigger next pending for this site
            triggerNext(site);
        }
    }

    private void triggerNext(String site) {
        DownloadItem next = null;
        for (DownloadItem it : items) {
            if (site.equals(it.getItem().getSourceAlias()) && it.getStatus() == DownloadStatus.PENDING) {
                next = it; break;
            }
        }
        if (next != null) start(next);
    }

    public void cancelSelected(Collection<DownloadItem> selected) {
        if (selected == null) return;
        for (DownloadItem di : selected) cancel(di);
    }

    public void cancel(DownloadItem di) {
        if (di == null) return;
        String site = di.getItem().getSourceAlias();
        
        if (di.getStatus() == DownloadStatus.RUNNING) {
            // Mark as canceled and free the site for next download
            di.setStatus(DownloadStatus.CANCELED);
            di.setSpeed("Annullato dall'utente");
            synchronized (runningSites) {
                runningSites.remove(site);
            }
            // Trigger next pending download for this site
            triggerNext(site);
        } else if (di.getStatus() == DownloadStatus.PENDING) {
            di.setStatus(DownloadStatus.CANCELED);
            di.setSpeed("Annullato");
        }
    }

    public void removeCompleted() {
        items.removeIf(di -> di.getStatus() == DownloadStatus.COMPLETED || di.getStatus() == DownloadStatus.CANCELED || di.getStatus() == DownloadStatus.FAILED);
    }
}
