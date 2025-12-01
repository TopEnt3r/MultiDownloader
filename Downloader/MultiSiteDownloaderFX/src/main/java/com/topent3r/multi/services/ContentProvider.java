package com.topent3r.multi.services;

import com.topent3r.multi.model.Episode;
import com.topent3r.multi.model.MediaItem;

import java.nio.file.Path;
import java.util.List;

public interface ContentProvider {

    String getDisplayName();

    List<MediaItem> search(String query) throws Exception;

    List<Episode> listEpisodes(MediaItem item) throws Exception;

    void download(MediaItem item, Episode episode, Path outputDir) throws Exception;
    
    default void download(MediaItem item, Episode episode, Path outputDir, DownloadCallback callback) throws Exception {
        download(item, episode, outputDir);
    }
    
    interface DownloadCallback {
        void onProgress(String message);
        void onSpeed(String speed);
    }
}
