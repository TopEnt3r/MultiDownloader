package com.topent3r.multi.services;

import java.io.File;

/**
 * Helper class for Python detection and script path resolution
 */
public class PythonHelper {
    
    private static String pythonCmd = null;
    
    public static String getPythonCommand() {
        if (pythonCmd != null) return pythonCmd;
        
        // Windows: try python, then common paths
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            String[] paths = {
                "python",
                "C:\\Python311\\python.exe",
                "C:\\Python312\\python.exe",
                "C:\\Python39\\python.exe",
                "C:\\Python310\\python.exe",
                "C:\\Program Files\\Python311\\python.exe",
                "C:\\Program Files\\Python312\\python.exe",
                System.getenv("LOCALAPPDATA") + "\\Programs\\Python\\Python311\\python.exe",
                System.getenv("LOCALAPPDATA") + "\\Programs\\Python\\Python312\\python.exe"
            };
            for (String p : paths) {
                if (p == null) continue;
                try {
                    Process proc = new ProcessBuilder(p, "--version").start();
                    if (proc.waitFor() == 0) {
                        pythonCmd = p;
                        return pythonCmd;
                    }
                } catch (Exception ignored) {}
            }
            pythonCmd = "python";
        } else {
            pythonCmd = "python3";
        }
        return pythonCmd;
    }
    
    public static String getScriptPath(String scriptName) {
        // Get app directory
        String appDir = System.getProperty("user.dir");
        
        String[] possiblePaths = {
            appDir + File.separator + "scripts" + File.separator + scriptName,
            appDir + File.separator + scriptName,
            "scripts" + File.separator + scriptName,
            // Development paths
            "/Users/andreacassan/AndroidStudioProjects/evolutix-panel 2/tmp/" + scriptName
        };
        
        for (String path : possiblePaths) {
            if (new File(path).exists()) {
                return path;
            }
        }
        
        // Default to scripts folder
        return "scripts" + File.separator + scriptName;
    }
}
