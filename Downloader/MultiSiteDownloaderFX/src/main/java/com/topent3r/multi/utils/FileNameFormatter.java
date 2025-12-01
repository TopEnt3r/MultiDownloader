package com.topent3r.multi.utils;

/**
 * Utility class for generating consistent file names across all providers.
 * 
 * Format standard:
 * - Film: "Titolo (anno).mp4"
 * - Serie TV: "Titolo 01x02 - Nome episodio.mp4" o "Titolo 01x02.mp4"
 */
public class FileNameFormatter {

    /**
     * Generate filename for a movie.
     * Format: "Titolo (anno).mp4"
     * 
     * @param title Movie title
     * @param year Year (can be null)
     * @param extension File extension (default: "mp4")
     * @return Formatted filename
     */
    public static String formatMovie(String title, String year, String extension) {
        String cleanTitle = sanitize(title == null ? "movie" : title);
        String ext = extension == null || extension.isBlank() ? "mp4" : extension;
        
        if (year != null && !year.isBlank()) {
            String cleanYear = year.replaceAll("[^0-9]", "");
            if (!cleanYear.isEmpty()) {
                return cleanTitle + " (" + cleanYear + ")." + ext;
            }
        }
        
        return cleanTitle + "." + ext;
    }

    /**
     * Generate filename for a TV episode.
     * Format: "Titolo 01x02.mp4" (NO episode title)
     * 
     * @param seriesTitle Series title
     * @param season Season number
     * @param episode Episode number
     * @param episodeTitle Episode title (IGNORED - never used)
     * @param extension File extension (default: "mp4")
     * @return Formatted filename
     */
    public static String formatEpisode(String seriesTitle, String season, String episode, String episodeTitle, String extension) {
        String cleanSeries = sanitize(seriesTitle == null ? "serie" : seriesTitle);
        String ss = formatNumber(season, 2);
        String ee = formatNumber(episode, 2);
        String ext = extension == null || extension.isBlank() ? "mp4" : extension;
        
        // Always format as "Title 01x02.ext" - NO episode title
        return cleanSeries + " " + ss + "x" + ee + "." + ext;
    }

    /**
     * Convenience method with default mp4 extension.
     */
    public static String formatMovie(String title, String year) {
        return formatMovie(title, year, "mp4");
    }

    /**
     * Convenience method with default mp4 extension.
     */
    public static String formatEpisode(String seriesTitle, String season, String episode, String episodeTitle) {
        return formatEpisode(seriesTitle, season, episode, episodeTitle, "mp4");
    }

    /**
     * Format number with leading zeros.
     * Example: formatNumber("3", 2) -> "03"
     */
    private static String formatNumber(String n, int digits) {
        if (n == null || n.isBlank()) return "00";
        try {
            int value = Integer.parseInt(n.replaceAll("[^0-9]", ""));
            return String.format("%0" + digits + "d", value);
        } catch (Exception e) {
            return n;
        }
    }

    /**
     * Sanitize filename by removing invalid characters.
     * Removes: \ / : * ? " < > |
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
