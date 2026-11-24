package dev.chrc.scanner;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class ScanResult {
    private final EnumMap<GemstoneType, Integer> counts;
    private final boolean valid;

    public ScanResult(Map<GemstoneType, Integer> counts, boolean valid) {
        this.counts = new EnumMap<>(GemstoneType.class);
        this.counts.putAll(counts);
        this.valid = valid;
    }

    public int count(GemstoneType type) {
        return counts.getOrDefault(type, 0);
    }

    public Map<GemstoneType, Integer> counts() {
        return Collections.unmodifiableMap(counts);
    }

    public boolean valid() {
        return valid;
    }
}
