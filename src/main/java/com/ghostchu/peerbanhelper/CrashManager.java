package com.ghostchu.peerbanhelper;

import com.ghostchu.peerbanhelper.alert.AlertLevel;
import com.ghostchu.peerbanhelper.alert.AlertManager;
import com.ghostchu.peerbanhelper.text.Lang;
import com.ghostchu.peerbanhelper.text.TranslationComponent;
import com.ghostchu.peerbanhelper.util.MsgUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j/**/
@Component
public final class CrashManager {
    private final File runningMarkerFile;
    private final File crashHistoryFile;
    private final AlertManager alertManager;
    private static final int MAX_CRASH_HISTORY_ENTRIES = 50;
    private static final int CRASH_FREQUENCY_WARNING_THRESHOLD = 3; // crashes within 24 hours
    private static final DateTimeFormatter CRASH_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public CrashManager(AlertManager alertManager) {
        this.runningMarkerFile = new File(Main.getDataDirectory(), "running.marker");
        this.crashHistoryFile = new File(Main.getDataDirectory(), "crash-history.log");
        this.alertManager = alertManager;
    }

    public void putRunningFlag() {
        try {
            // Create the running marker file with process ID for better tracking
            String processInfo = getProcessInfo();
            Files.writeString(runningMarkerFile.toPath(), processInfo, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            runningMarkerFile.deleteOnExit();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (runningMarkerFile.exists()) {
                        runningMarkerFile.delete();
                    }
                } catch (Exception e) {
                    // Ignore errors during shutdown
                }
            }));
        } catch (IOException e) {
            log.error("Unable to create running flag file", e);
        }
    }

    private String getProcessInfo() {
        try {
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            String timestamp = LocalDateTime.now().format(CRASH_TIMESTAMP_FORMAT);
            String jvmInfo = String.format("%s %s", 
                System.getProperty("java.vm.name", "Unknown VM"),
                System.getProperty("java.vm.version", "Unknown Version"));
            return String.format("PID: %s%nStarted: %s%nJVM: %s%n", pid, timestamp, jvmInfo);
        } catch (Exception e) {
            return "Unknown process info";
        }
    }

    public boolean isRunningFlagExists() {
        return runningMarkerFile.exists();
    }

    /**
     * Records a crash event in the crash history log
     */
    private void recordCrashEvent(String pid, String crashType) {
        try {
            String timestamp = LocalDateTime.now().format(CRASH_TIMESTAMP_FORMAT);
            String jvmInfo = String.format("%s %s", 
                System.getProperty("java.vm.name", "Unknown VM"),
                System.getProperty("java.vm.version", "Unknown Version"));
            String crashEntry = String.format("[%s] PID: %s, Type: %s, JVM: %s%n", 
                timestamp, pid, crashType, jvmInfo);
            
            // Append to crash history file
            Files.writeString(crashHistoryFile.toPath(), crashEntry, 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
            // Limit the crash history file size
            limitCrashHistorySize();
            
        } catch (IOException e) {
            log.error("Failed to record crash event", e);
        }
    }

    /**
     * Limits the crash history file to MAX_CRASH_HISTORY_ENTRIES
     */
    private void limitCrashHistorySize() {
        try {
            if (!crashHistoryFile.exists()) return;
            
            List<String> lines = Files.readAllLines(crashHistoryFile.toPath());
            if (lines.size() > MAX_CRASH_HISTORY_ENTRIES) {
                // Keep only the most recent entries
                List<String> recentLines = lines.subList(lines.size() - MAX_CRASH_HISTORY_ENTRIES, lines.size());
                Files.write(crashHistoryFile.toPath(), recentLines, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to limit crash history file size", e);
        }
    }

    /**
     * Checks recent crash frequency and returns crash count in last 24 hours
     */
    private int getRecentCrashCount() {
        try {
            if (!crashHistoryFile.exists()) return 0;
            
            List<String> lines = Files.readAllLines(crashHistoryFile.toPath());
            LocalDateTime twentyFourHoursAgo = LocalDateTime.now().minusHours(24);
            
            return (int) lines.stream()
                .filter(line -> line.startsWith("["))
                .map(line -> {
                    try {
                        String timestampStr = line.substring(1, line.indexOf("]"));
                        return LocalDateTime.parse(timestampStr, CRASH_TIMESTAMP_FORMAT);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(timestamp -> timestamp.isAfter(twentyFourHoursAgo))
                .collect(Collectors.counting())
                .intValue();
        } catch (Exception e) {
            log.error("Failed to count recent crashes", e);
            return 0;
        }
    }

    public void checkCrashRecovery(){
        // First check if we're recovering from a crash
        boolean isRecoveryMode = false;
        for (String startupArg : Main.getStartupArgs()) {
            if(!startupArg.startsWith("crashRecovery")) continue;
            String[] args = startupArg.split(":");
            if (args.length != 2) {
                log.error("Invalid crash recovery argument: {}", startupArg);
                continue;
            }
            String pid = args[1];
            processCrashRecovery(pid);
            isRecoveryMode = true;
            break;
        }
        
        // If not in recovery mode, check for unexpected shutdown
        if (!isRecoveryMode) {
            checkForUnexpectedShutdown();
        }
    }

    /**
     * Checks if the previous session ended unexpectedly (running marker file exists)
     */
    private void checkForUnexpectedShutdown() {
        if (isRunningFlagExists()) {
            log.warn("Detected unexpected shutdown - running marker file exists");
            try {
                String markerContent = Files.readString(runningMarkerFile.toPath());
                recordCrashEvent("unknown", "unexpected_shutdown");
                
                // Check crash frequency
                int recentCrashes = getRecentCrashCount();
                if (recentCrashes >= CRASH_FREQUENCY_WARNING_THRESHOLD) {
                    publishCrashFrequencyAlert(recentCrashes);
                }
                
                alertManager.publishAlert(true, AlertLevel.WARN, "unexpected-shutdown-" + UUID.randomUUID(),
                        new TranslationComponent(Lang.CRASH_MANAGER_UNEXPECTED_SHUTDOWN_TITLE),
                        new TranslationComponent(Lang.CRASH_MANAGER_UNEXPECTED_SHUTDOWN_DESCRIPTION,
                                markerContent.trim(),
                                MsgUtil.getDateFormatter().format(new Date())));
            } catch (Exception e) {
                log.error("Failed to process unexpected shutdown", e);
            }
        }
    }

    private void processCrashRecovery(String pid) {
        // Record the crash event
        recordCrashEvent(pid, "jvm_crash");
        
        // Check crash frequency
        int recentCrashes = getRecentCrashCount();
        
        File crashInDataDirectory = new File(Main.getDataDirectory(), "hs_err_pid" + pid + ".log");
        log.info("Looking for crash file in data directory: {}", crashInDataDirectory.getAbsolutePath());
        
        if(!crashInDataDirectory.exists()){
            crashInDataDirectory = new File(new File(Optional.ofNullable(System.getenv("LOCALAPPDATA")).orElse("."), "PeerBanHelper"), "hs_err_pid" + pid + ".log");
            log.info("Looking for crash file in local app data: {}", crashInDataDirectory.getAbsolutePath());
        }
        if(!crashInDataDirectory.exists()){
            crashInDataDirectory = new File(new File(System.getProperty("java.io.tmpdir", "")), "hs_err_pid" + pid + ".log");
            log.info("Looking for crash file in temp directory: {}", crashInDataDirectory.getAbsolutePath());
        }
        if(!crashInDataDirectory.exists()){
            crashInDataDirectory = new File(new File(System.getProperty("user.home", "")), "hs_err_pid" + pid + ".log");
            log.info("Looking for crash file in user home: {}", crashInDataDirectory.getAbsolutePath());
        }
        if(!crashInDataDirectory.exists()){
            crashInDataDirectory = new File("hs_err_pid" + pid + ".log"); // %WORKDIR%
            log.info("Looking for crash file in work directory: {}", crashInDataDirectory.getAbsolutePath());
        }
        
        // Try to preserve the crash file
        if (crashInDataDirectory.exists()) {
            preserveCrashFile(crashInDataDirectory, pid);
        } else {
            log.warn("No crash file found for pid: {}", pid);
        }
        
        // Check for frequent crashes and warn user
        if (recentCrashes >= CRASH_FREQUENCY_WARNING_THRESHOLD) {
            publishCrashFrequencyAlert(recentCrashes);
        }
        
        alertManager.publishAlert(true, AlertLevel.FATAL, "peerbanhelper-crash-recovery-"+ UUID.randomUUID(),
                new TranslationComponent(Lang.CRASH_MANAGER_CRASH_RECOVERY_ALERT_TITLE),
                new TranslationComponent(Lang.CRASH_MANAGER_CRASH_RECOVERY_ALERT_DESCRIPTION,
                        pid,
                        MsgUtil.getDateFormatter().format(new Date()),
                        !crashInDataDirectory.exists() ? "N/A" : crashInDataDirectory.getAbsolutePath(),
                        String.valueOf(recentCrashes)));
    }

    /**
     * Preserves crash file to a safe location for analysis
     */
    private void preserveCrashFile(File crashFile, String pid) {
        try {
            File crashArchiveDir = new File(Main.getDataDirectory(), "crash-reports");
            if (!crashArchiveDir.exists()) {
                crashArchiveDir.mkdirs();
            }
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File preservedCrashFile = new File(crashArchiveDir, String.format("hs_err_pid%s_%s.log", pid, timestamp));
            
            Files.copy(crashFile.toPath(), preservedCrashFile.toPath());
            log.info("Preserved crash file to: {}", preservedCrashFile.getAbsolutePath());
            
            // Clean up old crash reports (keep only last 10)
            cleanupOldCrashReports(crashArchiveDir);
            
        } catch (IOException e) {
            log.error("Failed to preserve crash file", e);
        }
    }

    /**
     * Cleans up old crash reports, keeping only the most recent ones
     */
    private void cleanupOldCrashReports(File crashArchiveDir) {
        try {
            File[] files = crashArchiveDir.listFiles((dir, name) -> name.startsWith("hs_err_") && name.endsWith(".log"));
            if (files != null && files.length > 10) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                // Delete older files, keep newest 10
                for (int i = 0; i < files.length - 10; i++) {
                    if (files[i].delete()) {
                        log.debug("Cleaned up old crash report: {}", files[i].getName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to cleanup old crash reports", e);
        }
    }

    /**
     * Publishes an alert about frequent crashes
     */
    private void publishCrashFrequencyAlert(int crashCount) {
        String alertId = "frequent-crashes-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        // Only show this alert once per day
        if (!alertManager.identifierAlertExistsIncludeRead(alertId)) {
            alertManager.publishAlert(true, AlertLevel.FATAL, alertId,
                    new TranslationComponent(Lang.CRASH_MANAGER_FREQUENT_CRASHES_TITLE),
                    new TranslationComponent(Lang.CRASH_MANAGER_FREQUENT_CRASHES_DESCRIPTION,
                            String.valueOf(crashCount),
                            getJvmRecommendation()));
        }
    }

    /**
     * Gets JVM recommendation based on current JVM
     */
    private String getJvmRecommendation() {
        var bean = ManagementFactory.getRuntimeMXBean();
        String vendor = bean.getVmVendor();
        String version = bean.getVmVersion();
        String vmName = bean.getVmName();
        
        log.info("Current JVM: {} {} ({})", vendor, version, vmName);
        
        if (vendor.contains("JetBrains")) {
            return "Consider updating to the latest JetBrains Runtime (JBR) version. " +
                   "Check https://github.com/JetBrains/JetBrainsRuntime/releases for newer versions.";
        } else if (vendor.contains("Eclipse") && vendor.contains("Adoptium")) {
            return "Switch to JetBrains Runtime (JBR) which has better compatibility with PeerBanHelper on Windows. " +
                   "Eclipse Adoptium (Temurin) may experience awt.dll related crashes.";
        } else if (vendor.contains("Azul")) {
            return "Switch to JetBrains Runtime (JBR) which has better compatibility with PeerBanHelper on Windows. " +
                   "Azul Zulu may experience graphics-related crashes.";
        } else if (vendor.contains("BellSoft")) {
            return "Switch to JetBrains Runtime (JBR) which has better compatibility with PeerBanHelper on Windows. " +
                   "BellSoft Liberica may experience compatibility issues.";
        } else if (vendor.contains("Oracle") || vendor.contains("OpenJDK")) {
            return "Switch to JetBrains Runtime (JBR) which has better compatibility with PeerBanHelper on Windows. " +
                   "Oracle/OpenJDK may lack necessary patches for GUI stability.";
        } else {
            return "Switch to JetBrains Runtime (JBR) which has better compatibility with PeerBanHelper on Windows. " +
                   "Unknown JVM vendor (" + vendor + ") may not include necessary patches.";
        }
    }

    /**
     * Provides system information for crash reports
     */
    public String getSystemInfo() {
        try {
            var bean = ManagementFactory.getRuntimeMXBean();
            var osMXBean = ManagementFactory.getOperatingSystemMXBean();
            var memoryMXBean = ManagementFactory.getMemoryMXBean();
            
            StringBuilder info = new StringBuilder();
            info.append("=== System Information ===\n");
            info.append("OS: ").append(osMXBean.getName()).append(" ").append(osMXBean.getVersion()).append("\n");
            info.append("Architecture: ").append(osMXBean.getArch()).append("\n");
            info.append("Available Processors: ").append(osMXBean.getAvailableProcessors()).append("\n");
            
            info.append("\n=== JVM Information ===\n");
            info.append("JVM Name: ").append(bean.getVmName()).append("\n");
            info.append("JVM Vendor: ").append(bean.getVmVendor()).append("\n");
            info.append("JVM Version: ").append(bean.getVmVersion()).append("\n");
            info.append("Java Version: ").append(System.getProperty("java.version")).append("\n");
            info.append("Java Home: ").append(System.getProperty("java.home")).append("\n");
            
            info.append("\n=== Memory Information ===\n");
            var heapMemory = memoryMXBean.getHeapMemoryUsage();
            var nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();
            info.append("Heap Memory: ").append(formatBytes(heapMemory.getUsed())).append(" / ").append(formatBytes(heapMemory.getMax())).append("\n");
            info.append("Non-Heap Memory: ").append(formatBytes(nonHeapMemory.getUsed())).append(" / ").append(formatBytes(nonHeapMemory.getMax())).append("\n");
            
            info.append("\n=== PeerBanHelper Information ===\n");
            info.append("Version: ").append(Main.getMeta().getVersion()).append("\n");
            info.append("Data Directory: ").append(Main.getDataDirectory().getAbsolutePath()).append("\n");
            info.append("Config Directory: ").append(Main.getConfigDirectory().getAbsolutePath()).append("\n");
            
            return info.toString();
        } catch (Exception e) {
            return "Failed to collect system information: " + e.getMessage();
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Generates a comprehensive crash summary report for easy bug reporting
     */
    public String generateCrashSummary() {
        try {
            StringBuilder summary = new StringBuilder();
            summary.append("# PeerBanHelper Crash Report\n\n");
            
            // Basic info
            summary.append("**Version:** ").append(Main.getMeta().getVersion()).append("\n");
            summary.append("**Branch:** ").append(Main.getMeta().getBranch()).append("\n");
            summary.append("**Commit:** ").append(Main.getMeta().getAbbrev()).append("\n");
            summary.append("**Report Generated:** ").append(LocalDateTime.now().format(CRASH_TIMESTAMP_FORMAT)).append("\n\n");
            
            // Crash frequency
            int recentCrashCount = getRecentCrashCount();
            summary.append("**Recent Crashes (24h):** ").append(recentCrashCount).append("\n\n");
            
            // System info
            summary.append("## System Information\n");
            summary.append("```\n").append(getSystemInfo()).append("```\n\n");
            
            // JVM recommendation
            summary.append("## Recommendation\n");
            summary.append(getJvmRecommendation()).append("\n\n");
            
            // Recent crash history
            if (crashHistoryFile.exists()) {
                try {
                    List<String> recentCrashHistory = Files.readAllLines(crashHistoryFile.toPath())
                        .stream()
                        .limit(10)
                        .collect(Collectors.toList());
                    
                    if (!recentCrashHistory.isEmpty()) {
                        summary.append("## Recent Crash History\n");
                        summary.append("```\n");
                        for (String crash : recentCrashHistory) {
                            summary.append(crash).append("\n");
                        }
                        summary.append("```\n\n");
                    }
                } catch (IOException e) {
                    summary.append("Failed to read crash history: ").append(e.getMessage()).append("\n\n");
                }
            }
            
            // Instructions
            summary.append("## Instructions\n");
            summary.append("1. Please share this report when reporting the crash issue\n");
            summary.append("2. If available, also attach the JVM crash dump file (hs_err_*.log)\n");
            summary.append("3. Include steps to reproduce if the crash is reproducible\n");
            summary.append("4. Consider switching to JetBrains Runtime (JBR) if using other JVM vendors\n");
            
            return summary.toString();
        } catch (Exception e) {
            return "Failed to generate crash summary: " + e.getMessage();
        }
    }

    /**
     * Exports crash summary to a file for easy sharing
     */
    public File exportCrashSummary() {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File summaryFile = new File(Main.getDataDirectory(), "crash-summary-" + timestamp + ".md");
            
            String summary = generateCrashSummary();
            Files.writeString(summaryFile.toPath(), summary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            log.info("Crash summary exported to: {}", summaryFile.getAbsolutePath());
            return summaryFile;
        } catch (IOException e) {
            log.error("Failed to export crash summary", e);
            return null;
        }
    }
}
