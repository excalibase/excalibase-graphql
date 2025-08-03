package io.github.excalibase.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comprehensive benchmark measurement and reporting utilities for enterprise-scale testing.
 */
@Component
public class BenchmarkUtils {
    
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkUtils.class);
    private static final Map<String, List<BenchmarkResult>> results = new ConcurrentHashMap<>();
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    /**
     * Benchmark result data structure
     */
    public static class BenchmarkResult {
        private final String testName;
        private final String operation;
        private final long durationMs;
        private final long memoryUsedMB;
        private final int recordCount;
        private final boolean success;
        private final LocalDateTime timestamp;
        private final Map<String, Object> metadata;
        
        public BenchmarkResult(String testName, String operation, long durationMs, 
                             long memoryUsedMB, int recordCount, boolean success, 
                             Map<String, Object> metadata) {
            this.testName = testName;
            this.operation = operation;
            this.durationMs = durationMs;
            this.memoryUsedMB = memoryUsedMB;
            this.recordCount = recordCount;
            this.success = success;
            this.timestamp = LocalDateTime.now();
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
        
        // Getters
        public String getTestName() { return testName; }
        public String getOperation() { return operation; }
        public long getDurationMs() { return durationMs; }
        public long getMemoryUsedMB() { return memoryUsedMB; }
        public int getRecordCount() { return recordCount; }
        public boolean isSuccess() { return success; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public Map<String, Object> getMetadata() { return metadata; }
        
        @Override
        public String toString() {
            return String.format("[%s] %s - %s: %dms, %dMB, %d records, success=%s", 
                    timestamp.format(TIMESTAMP_FORMAT), testName, operation, 
                    durationMs, memoryUsedMB, recordCount, success);
        }
    }
    
    /**
     * Performance measurement wrapper
     */
    public static class PerformanceMeasurement {
        private final long startTime;
        private final long startMemory;
        private final String testName;
        private final String operation;
        private int recordCount = 0;
        private Map<String, Object> metadata = new HashMap<>();
        
        public PerformanceMeasurement(String testName, String operation) {
            this.testName = testName;
            this.operation = operation;
            this.startTime = System.currentTimeMillis();
            this.startMemory = getCurrentMemoryUsage();
            
            logger.debug("🔍 Starting benchmark: {} - {}", testName, operation);
        }
        
        public PerformanceMeasurement recordCount(int count) {
            this.recordCount = count;
            return this;
        }
        
        public PerformanceMeasurement metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public PerformanceMeasurement metadata(Map<String, Object> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }
        
        public BenchmarkResult complete() {
            return complete(true);
        }
        
        public BenchmarkResult complete(boolean success) {
            long endTime = System.currentTimeMillis();
            long endMemory = getCurrentMemoryUsage();
            
            long durationMs = endTime - startTime;
            long memoryUsedMB = (endMemory - startMemory) / 1024 / 1024;
            
            BenchmarkResult result = new BenchmarkResult(
                    testName, operation, durationMs, memoryUsedMB, 
                    recordCount, success, metadata
            );
            
            // Store result
            results.computeIfAbsent(testName, k -> new ArrayList<>()).add(result);
            
            // Log result
            String status = success ? "✅" : "❌";
            logger.info("{} Benchmark completed: {} - {} in {}ms ({}MB memory)", 
                    status, testName, operation, durationMs, memoryUsedMB);
            
            if (durationMs > 5000) {
                logger.warn("⚠️ Slow operation detected: {} took {}ms", operation, durationMs);
            }
            
            return result;
        }
    }
    
    /**
     * Start a new performance measurement
     */
    public static PerformanceMeasurement startMeasurement(String testName, String operation) {
        return new PerformanceMeasurement(testName, operation);
    }
    
    /**
     * Quick measurement for simple operations
     */
    public static BenchmarkResult measureOperation(String testName, String operation, Runnable task) {
        PerformanceMeasurement measurement = startMeasurement(testName, operation);
        boolean success = true;
        
        try {
            task.run();
        } catch (Exception e) {
            logger.error("❌ Benchmark operation failed: {} - {}", testName, operation, e);
            success = false;
        }
        
        return measurement.complete(success);
    }
    
    /**
     * Measure database query performance
     */
    public static <T> T measureQuery(String testName, String queryType, 
                                   java.util.function.Supplier<T> querySupplier) {
        PerformanceMeasurement measurement = startMeasurement(testName, queryType);
        T result = null;
        boolean success = true;
        
        try {
            result = querySupplier.get();
            
            // Try to extract record count if result is a collection
            if (result instanceof java.util.Collection) {
                measurement.recordCount(((java.util.Collection<?>) result).size());
            }
            
        } catch (Exception e) {
            logger.error("❌ Query benchmark failed: {} - {}", testName, queryType, e);
            success = false;
        }
        
        measurement.complete(success);
        return result;
    }
    
    /**
     * Measure concurrent operations
     */
    public static List<BenchmarkResult> measureConcurrentOperations(
            String testName, int threadCount, List<Runnable> operations) {
        
        List<BenchmarkResult> concurrentResults = new ArrayList<>();
        java.util.concurrent.ExecutorService executor = 
                java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        
        PerformanceMeasurement overallMeasurement = 
                startMeasurement(testName, "concurrent_operations");
        
        try {
            List<java.util.concurrent.Future<BenchmarkResult>> futures = new ArrayList<>();
            
            for (int i = 0; i < operations.size(); i++) {
                final int operationIndex = i;
                final Runnable operation = operations.get(i);
                
                futures.add(executor.submit(() -> {
                    PerformanceMeasurement individualMeasurement = 
                            startMeasurement(testName, "concurrent_op_" + operationIndex);
                    boolean success = true;
                    
                    try {
                        operation.run();
                    } catch (Exception e) {
                        logger.error("❌ Concurrent operation {} failed", operationIndex, e);
                        success = false;
                    }
                    
                    return individualMeasurement.complete(success);
                }));
            }
            
            // Wait for all operations to complete
            for (java.util.concurrent.Future<BenchmarkResult> future : futures) {
                try {
                    concurrentResults.add(future.get());
                } catch (Exception e) {
                    logger.error("❌ Error waiting for concurrent operation", e);
                }
            }
            
        } finally {
            executor.shutdown();
        }
        
        overallMeasurement
                .recordCount(operations.size())
                .metadata("thread_count", threadCount)
                .metadata("successful_operations", 
                         concurrentResults.stream().mapToInt(r -> r.isSuccess() ? 1 : 0).sum())
                .complete();
        
        return concurrentResults;
    }
    
    /**
     * Generate comprehensive benchmark report
     */
    public static void generateReport(String outputDir) {
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(outputDir));
            
            generateCSVReport(outputDir + "/benchmark-results.csv");
            generateJSONReport(outputDir + "/benchmark-results.json");
            generateHTMLReport(outputDir + "/benchmark-report.html");
            generateSummaryReport(outputDir + "/benchmark-summary.txt");
            
            logger.info("📊 Benchmark reports generated in: {}", outputDir);
            
        } catch (Exception e) {
            logger.error("❌ Failed to generate benchmark reports", e);
        }
    }
    
    /**
     * Generate CSV report for spreadsheet analysis
     */
    private static void generateCSVReport(String filename) throws IOException {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("TestName,Operation,DurationMs,MemoryUsedMB,RecordCount,Success,Timestamp,Metadata\n");
            
            for (Map.Entry<String, List<BenchmarkResult>> entry : results.entrySet()) {
                for (BenchmarkResult result : entry.getValue()) {
                    writer.write(String.format("%s,%s,%d,%d,%d,%s,%s,\"%s\"\n",
                            result.getTestName(),
                            result.getOperation(),
                            result.getDurationMs(),
                            result.getMemoryUsedMB(),
                            result.getRecordCount(),
                            result.isSuccess(),
                            result.getTimestamp().format(TIMESTAMP_FORMAT),
                            result.getMetadata().toString().replace("\"", "'")
                    ));
                }
            }
        }
    }
    
    /**
     * Generate JSON report for programmatic analysis
     */
    private static void generateJSONReport(String filename) throws IOException {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("{\n  \"benchmark_results\": {\n");
            
            boolean firstTest = true;
            for (Map.Entry<String, List<BenchmarkResult>> entry : results.entrySet()) {
                if (!firstTest) writer.write(",\n");
                writer.write(String.format("    \"%s\": [\n", entry.getKey()));
                
                boolean firstResult = true;
                for (BenchmarkResult result : entry.getValue()) {
                    if (!firstResult) writer.write(",\n");
                    writer.write(String.format(
                            "      {\n" +
                            "        \"operation\": \"%s\",\n" +
                            "        \"duration_ms\": %d,\n" +
                            "        \"memory_used_mb\": %d,\n" +
                            "        \"record_count\": %d,\n" +
                            "        \"success\": %s,\n" +
                            "        \"timestamp\": \"%s\"\n" +
                            "      }",
                            result.getOperation(),
                            result.getDurationMs(),
                            result.getMemoryUsedMB(),
                            result.getRecordCount(),
                            result.isSuccess(),
                            result.getTimestamp().format(TIMESTAMP_FORMAT)
                    ));
                    firstResult = false;
                }
                
                writer.write("\n    ]");
                firstTest = false;
            }
            
            writer.write("\n  }\n}");
        }
    }
    
    /**
     * Generate HTML report for visual analysis
     */
    private static void generateHTMLReport(String filename) throws IOException {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Excalibase GraphQL Benchmark Report</title>
                    <style>
                        body { font-family: Arial, sans-serif; margin: 20px; }
                        h1, h2 { color: #2196f3; }
                        table { border-collapse: collapse; width: 100%; margin: 20px 0; }
                        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                        th { background-color: #f2f2f2; }
                        .success { color: #4caf50; }
                        .failure { color: #f44336; }
                        .slow { background-color: #fff3cd; }
                        .fast { background-color: #d4edda; }
                        .summary { background-color: #e3f2fd; padding: 15px; border-radius: 5px; }
                    </style>
                </head>
                <body>
                    <h1>🚀 Excalibase GraphQL Enterprise Benchmark Report</h1>
                    <div class="summary">
                        <h2>📊 Executive Summary</h2>
                """);
            
            // Calculate summary statistics
            int totalTests = results.values().stream().mapToInt(List::size).sum();
            long avgDuration = results.values().stream()
                    .flatMap(List::stream)
                    .mapToLong(BenchmarkResult::getDurationMs)
                    .sum() / Math.max(totalTests, 1);
            
            writer.write(String.format("""
                        <p><strong>Total Tests:</strong> %d</p>
                        <p><strong>Average Duration:</strong> %d ms</p>
                        <p><strong>Report Generated:</strong> %s</p>
                    </div>
                    
                    <h2>📈 Detailed Results</h2>
                """, totalTests, avgDuration, LocalDateTime.now().format(TIMESTAMP_FORMAT)));
            
            for (Map.Entry<String, List<BenchmarkResult>> entry : results.entrySet()) {
                writer.write(String.format("<h3>%s</h3>\n", entry.getKey()));
                writer.write("<table>\n");
                writer.write("<tr><th>Operation</th><th>Duration (ms)</th><th>Memory (MB)</th><th>Records</th><th>Status</th><th>Timestamp</th></tr>\n");
                
                for (BenchmarkResult result : entry.getValue()) {
                    String rowClass = result.getDurationMs() > 2000 ? "slow" : "fast";
                    String statusClass = result.isSuccess() ? "success" : "failure";
                    String statusIcon = result.isSuccess() ? "✅" : "❌";
                    
                    writer.write(String.format(
                            "<tr class=\"%s\"><td>%s</td><td>%d</td><td>%d</td><td>%d</td><td class=\"%s\">%s</td><td>%s</td></tr>\n",
                            rowClass,
                            result.getOperation(),
                            result.getDurationMs(),
                            result.getMemoryUsedMB(),
                            result.getRecordCount(),
                            statusClass,
                            statusIcon,
                            result.getTimestamp().format(TIMESTAMP_FORMAT)
                    ));
                }
                
                writer.write("</table>\n");
            }
            
            writer.write("</body></html>");
        }
    }
    
    /**
     * Generate summary report
     */
    private static void generateSummaryReport(String filename) throws IOException {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("EXCALIBASE GRAPHQL ENTERPRISE BENCHMARK SUMMARY\n");
            writer.write("=" .repeat(50) + "\n\n");
            
            for (Map.Entry<String, List<BenchmarkResult>> entry : results.entrySet()) {
                writer.write(String.format("TEST SUITE: %s\n", entry.getKey()));
                writer.write("-".repeat(30) + "\n");
                
                List<BenchmarkResult> testResults = entry.getValue();
                long totalDuration = testResults.stream().mapToLong(BenchmarkResult::getDurationMs).sum();
                long avgDuration = totalDuration / Math.max(testResults.size(), 1);
                long maxDuration = testResults.stream().mapToLong(BenchmarkResult::getDurationMs).max().orElse(0);
                long successCount = testResults.stream().mapToLong(r -> r.isSuccess() ? 1 : 0).sum();
                
                writer.write(String.format("Operations: %d\n", testResults.size()));
                writer.write(String.format("Success Rate: %.1f%%\n", (successCount * 100.0) / testResults.size()));
                writer.write(String.format("Total Duration: %d ms\n", totalDuration));
                writer.write(String.format("Average Duration: %d ms\n", avgDuration));
                writer.write(String.format("Max Duration: %d ms\n", maxDuration));
                writer.write("\n");
            }
        }
    }
    
    /**
     * Get all benchmark results for comprehensive reporting
     */
    public static Map<String, List<BenchmarkResult>> getAllResults() {
        return new HashMap<>(results);
    }
    
    /**
     * Clear all benchmark results
     */
    public static void clearResults() {
        results.clear();
        logger.info("🧹 Benchmark results cleared");
    }
    
    /**
     * Get current memory usage in bytes
     */
    private static long getCurrentMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    /**
     * Force garbage collection and return memory usage
     */
    public static long getMemoryUsageAfterGC() {
        System.gc();
        try {
            Thread.sleep(100); // Give GC time to complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return getCurrentMemoryUsage();
    }
    
    /**
     * Check if system has enough memory for large-scale testing
     */
    public static boolean hasEnoughMemoryForTesting() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long freeMemory = runtime.freeMemory();
        long availableMemory = maxMemory - (runtime.totalMemory() - freeMemory);
        
        // Require at least 2GB available memory
        boolean hasEnoughMemory = availableMemory > 2L * 1024 * 1024 * 1024;
        
        if (!hasEnoughMemory) {
            logger.warn("⚠️ Insufficient memory for enterprise testing. Available: {}MB, Required: 2048MB", 
                    availableMemory / 1024 / 1024);
        }
        
        return hasEnoughMemory;
    }
}