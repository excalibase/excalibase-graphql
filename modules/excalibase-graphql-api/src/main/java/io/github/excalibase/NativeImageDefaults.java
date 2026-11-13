package io.github.excalibase;

import java.util.Properties;

/** System properties a native image needs before Netty starts; the JVM build is left as it is. */
final class NativeImageDefaults {

    private static final String ALLOCATOR = "io.netty.allocator.type";

    private NativeImageDefaults() {
    }

    /** Netty's default allocator stalls and then fails a request body over about 256 KB in a native image. */
    static void apply(Properties properties) {
        if (properties.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            properties.putIfAbsent(ALLOCATOR, "unpooled");
        }
    }
}
