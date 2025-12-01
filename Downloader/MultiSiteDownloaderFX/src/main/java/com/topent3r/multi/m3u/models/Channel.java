package com.topent3r.multi.m3u.models;

/** POJO usato dalla TableView con PropertyValueFactory. */
public class Channel {
    private final String id;
    private final String name;
    private final String group;
    private final String url;
    private final String tvgId;
    private final String logo;
    private final boolean vod;

    public Channel(String id, String name, String group, String url, String tvgId, String logo, boolean vod) {
        this.id = id;
        this.name = name;
        this.group = group;
        this.url = url;
        this.tvgId = tvgId;
        this.logo = logo;
        this.vod = vod;
    }

    public String getId()    { return id; }
    public String getName()  { return name; }
    public String getGroup() { return group; }
    public String getUrl()   { return url; }
    public String getTvgId() { return tvgId; }
    public String getLogo()  { return logo; }
    public boolean isVod()   { return vod; }
}
