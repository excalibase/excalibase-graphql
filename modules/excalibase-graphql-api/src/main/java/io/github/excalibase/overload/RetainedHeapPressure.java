package io.github.excalibase.overload;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.function.DoubleSupplier;

/** High when the heap still held after the last GC passes a share of the maximum heap. */
public class RetainedHeapPressure implements HeapPressure {

    private final double thresholdShare;
    private final DoubleSupplier retainedShare;

    public RetainedHeapPressure(int thresholdPercent) {
        this(thresholdPercent, RetainedHeapPressure::jvmRetainedShare);
    }

    RetainedHeapPressure(int thresholdPercent, DoubleSupplier retainedShare) {
        if (thresholdPercent < 1 || thresholdPercent > 100) {
            throw new IllegalArgumentException("heap threshold must be 1..100 percent, got " + thresholdPercent);
        }
        this.thresholdShare = thresholdPercent / 100.0;
        this.retainedShare = retainedShare;
    }

    @Override
    public boolean isHigh() {
        return retainedShare.getAsDouble() > thresholdShare;
    }

    static double jvmRetainedShare() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        long retained = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage afterGc = pool.getType() == MemoryType.HEAP ? pool.getCollectionUsage() : null;
            if (afterGc != null) {
                retained += afterGc.getUsed();
            }
        }
        return Math.min(1.0, (double) retained / maxHeap);
    }
}
