package mdm.capstone;

public record PredictorProfile(String batteryId, double observedFraction, String method,
        long elapsedNanos, long heapBeforeBytes, long heapAfterBytes, String status) {
    public long elapsedMillis() {
        return elapsedNanos / 1_000_000L;
    }

    public long heapDeltaBytes() {
        return heapAfterBytes - heapBeforeBytes;
    }
}
