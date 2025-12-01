package com.topent3r.multi.model;

public class MediaItem {
    private String id;
    private String title;
    private String type;
    private String source;
    private String sourceAlias;
    private String year;
    private String url;
    private String pathId;
    private String slug;

    public MediaItem(String id, String title, String type, String source, String sourceAlias, String year) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.source = source;
        this.sourceAlias = sourceAlias;
        this.year = year;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getType() {
        return type;
    }

    public String getSource() {
        return source;
    }

    public String getSourceAlias() {
        return sourceAlias;
    }

    public String getYear() {
        return year;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    public String getPathId() {
        return pathId;
    }
    
    public void setPathId(String pathId) {
        this.pathId = pathId;
    }
    
    public String getSlug() {
        return slug;
    }
    
    public void setSlug(String slug) {
        this.slug = slug;
    }
}

