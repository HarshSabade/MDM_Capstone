package mdm.capstone;

public record BootstrapInterval(double lowerRul, double medianRul, double upperRul,
        int requestedSamples, int successfulSamples, String status) {
    public static BootstrapInterval unavailable(int requestedSamples, String status) {
        return new BootstrapInterval(Double.NaN, Double.NaN, Double.NaN,
                requestedSamples, 0, status);
    }
}
