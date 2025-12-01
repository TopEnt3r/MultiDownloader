package com.topent3r.multi.model;

public class Episode {
    private final String id;
    private final String season;
    private final String episode;
    private final String title;
    private int index = -1;
    private String url;

    public Episode(String season, String episode, String title) {
        this.id = null;
        this.season = season;
        this.episode = episode;
        this.title = title;
    }

    public Episode(String id, String season, String episode, String title) {
        this.id = id;
        this.season = season;
        this.episode = episode;
        this.title = title;
    }

    public String getSeason() {
        return season;
    }

    public String getEpisode() {
        return episode;
    }

    public String getTitle() {
        return title;
    }

    public String getId() {
        return id;
    }
    
    public int getIndex() {
        return index;
    }
    
    public void setIndex(int index) {
        this.index = index;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public String toString() {
        String ep = episode == null ? "" : episode;
        String t = title == null ? "" : title;
        return (ep.isEmpty() ? "" : ("E" + ep + " - ")) + t;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Episode episode1 = (Episode) o;
        if (season != null ? !season.equals(episode1.season) : episode1.season != null) return false;
        return episode != null ? episode.equals(episode1.episode) : episode1.episode == null;
    }

    @Override
    public int hashCode() {
        int result = season != null ? season.hashCode() : 0;
        result = 31 * result + (episode != null ? episode.hashCode() : 0);
        return result;
    }
}

