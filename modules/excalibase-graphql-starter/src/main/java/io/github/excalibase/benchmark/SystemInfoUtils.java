package io.github.excalibase.benchmark;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;

/**
 * Utility class for gathering system information including CPU and memory metrics.
 * Used for benchmarking and performance monitoring.
 */
public class SystemInfoUtils {

    private static final OperatingSystemMXBean osBean = 
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private static final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

    /**
     * Get current CPU usage as a percentage (0-100)
     * @return CPU usage percentage
     */
    public static double getCpuUsage() {
        return osBean.getProcessCpuLoad() * 100.0;
    }

    /**
     * Get system CPU usage as a percentage (0-100)
     * @return System CPU usage percentage
     */
    public static double getSystemCpuUsage() {
        return osBean.getSystemCpuLoad() * 100.0;
    }

    /**
     * Get total physical memory in bytes
     * @return Total physical memory in bytes
     */
    public static long getTotalPhysicalMemory() {
        return osBean.getTotalPhysicalMemorySize();
    }

    /**
     * Get free physical memory in bytes
     * @return Free physical memory in bytes
     */
    public static long getFreePhysicalMemory() {
        return osBean.getFreePhysicalMemorySize();
    }

    /**
     * Get used physical memory in bytes
     * @return Used physical memory in bytes
     */
    public static long getUsedPhysicalMemory() {
        return getTotalPhysicalMemory() - getFreePhysicalMemory();
    }

    /**
     * Get physical memory usage as a percentage (0-100)
     * @return Memory usage percentage
     */
    public static double getMemoryUsagePercentage() {
        long total = getTotalPhysicalMemory();
        long used = getUsedPhysicalMemory();
        return total > 0 ? (used * 100.0) / total : 0.0;
    }

    /**
     * Get JVM heap memory usage
     * @return MemoryUsage object containing heap memory info
     */
    public static MemoryUsage getHeapMemoryUsage() {
        return memoryBean.getHeapMemoryUsage();
    }

    /**
     * Get JVM non-heap memory usage
     * @return MemoryUsage object containing non-heap memory info
     */
    public static MemoryUsage getNonHeapMemoryUsage() {
        return memoryBean.getNonHeapMemoryUsage();
    }

    /**
     * Get number of available processors
     * @return Number of available processors
     */
    public static int getAvailableProcessors() {
        return osBean.getAvailableProcessors();
    }

    /**
     * Get JVM uptime in milliseconds
     * @return JVM uptime in milliseconds
     */
    public static long getJvmUptime() {
        return runtimeBean.getUptime();
    }

    /**
     * Print comprehensive system information to console
     */
    public static void printSystemInfo() {
        System.out.println("=== APPLICATION INFORMATION ===");
        System.out.printf("Application: Excalibase GraphQL API%n");
        System.out.printf("Available CPU Cores: %d%n", getAvailableProcessors());
        System.out.println();

        System.out.println("=== APPLICATION CPU USAGE ===");
        System.out.printf("Application CPU Usage: %.2f%%%n", getCpuUsage());
        System.out.println();

        System.out.println("=== APPLICATION MEMORY USAGE ===");
        System.out.printf("JVM Heap Used: %s%n", formatBytes(getHeapMemoryUsage().getUsed()));
        System.out.printf("JVM Heap Max: %s%n", formatBytes(getHeapMemoryUsage().getMax()));
        System.out.printf("JVM Non-Heap Used: %s%n", formatBytes(getNonHeapMemoryUsage().getUsed()));
        System.out.printf("Used Physical Memory: %s%n", formatBytes(getUsedPhysicalMemory()));
        System.out.printf("Memory Usage: %.2f%%%n", getMemoryUsagePercentage());
        System.out.println();

        MemoryUsage heapUsage = getHeapMemoryUsage();
        System.out.println("=== JVM HEAP MEMORY ===");
        System.out.printf("Used: %s%n", formatBytes(heapUsage.getUsed()));
        System.out.printf("Committed: %s%n", formatBytes(heapUsage.getCommitted()));
        System.out.printf("Max: %s%n", formatBytes(heapUsage.getMax()));
        System.out.printf("Heap Usage: %.2f%%%n", 
            heapUsage.getMax() > 0 ? (heapUsage.getUsed() * 100.0) / heapUsage.getMax() : 0.0);
        System.out.println();

        System.out.printf("JVM Uptime: %s%n", formatDuration(getJvmUptime()));
    }

    /**
     * Get application-specific information as a formatted string
     * @return Formatted application information string
     */
    public static String getSystemInfoAsString() {
        StringBuilder sb = new StringBuilder();
        MemoryUsage heapUsage = getHeapMemoryUsage();
        double heapPercent = heapUsage.getMax() > 0 ? (heapUsage.getUsed() * 100.0) / heapUsage.getMax() : 0.0;
        
        sb.append("Application CPU: ").append(String.format("%.2f%%", getCpuUsage()));
        sb.append(" | JVM Heap: ").append(String.format("%.2f%%", heapPercent));
        sb.append(" (").append(formatBytes(heapUsage.getUsed())).append("/");
        sb.append(formatBytes(heapUsage.getMax())).append(")");
        sb.append(" | Available Cores: ").append(getAvailableProcessors());
        return sb.toString();
    }

    /**
     * Format bytes to human-readable format
     * @param bytes Number of bytes
     * @return Formatted string (e.g., "1.5 GB")
     */
    private static String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double size = bytes;
        
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        
        if (unitIndex == 0) {
            return String.format("%d %s", (long) size, units[unitIndex]);
        } else {
            return String.format("%.2f %s", size, units[unitIndex]);
        }
    }

    /**
     * Format duration in milliseconds to human-readable format
     * @param millis Duration in milliseconds
     * @return Formatted string (e.g., "2h 30m 15s")
     */
    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * Create a simple application info summary for logging
     * @return Compact application info string
     */
    public static String getCompactSystemInfo() {
        MemoryUsage heapUsage = getHeapMemoryUsage();
        double heapPercent = heapUsage.getMax() > 0 ? (heapUsage.getUsed() * 100.0) / heapUsage.getMax() : 0.0;
        return String.format("App CPU: %.1f%% | JVM Heap: %.1f%% (%s/%s) | Cores: %d", 
            getCpuUsage(), heapPercent, formatBytes(heapUsage.getUsed()), 
            formatBytes(heapUsage.getMax()), getAvailableProcessors());
    }
}