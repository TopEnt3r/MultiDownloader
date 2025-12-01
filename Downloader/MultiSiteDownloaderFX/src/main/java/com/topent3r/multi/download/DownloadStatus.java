package com.topent3r.multi.download;

public enum DownloadStatus {
    PENDING("In coda"),
    RUNNING("In download"),
    COMPLETED("Completato"),
    FAILED("Errore"),
    CANCELED("Annullato");
    
    private final String displayName;
    
    DownloadStatus(String displayName) {
        this.displayName = displayName;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}
