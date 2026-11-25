package io.github.excalibase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NativeImageDefaultsTest {

    @Test
    @DisplayName("a native image serves bodies over 256 KB only with the unpooled allocator, so it is the default there")
    void nativeImageUsesUnpooledAllocator() {
        Properties properties = new Properties();
        properties.setProperty("org.graalvm.nativeimage.imagecode", "runtime");

        NativeImageDefaults.apply(properties);

        assertThat(properties.getProperty("io.netty.allocator.type")).isEqualTo("unpooled");
    }

    @Test
    @DisplayName("an allocator chosen by the operator is kept")
    void operatorChoiceWins() {
        Properties properties = new Properties();
        properties.setProperty("org.graalvm.nativeimage.imagecode", "runtime");
        properties.setProperty("io.netty.allocator.type", "pooled");

        NativeImageDefaults.apply(properties);

        assertThat(properties.getProperty("io.netty.allocator.type")).isEqualTo("pooled");
    }

    @Test
    @DisplayName("the JVM keeps Netty's own allocator")
    void jvmIsUntouched() {
        Properties properties = new Properties();

        NativeImageDefaults.apply(properties);

        assertThat(properties).doesNotContainKey("io.netty.allocator.type");
    }
}
