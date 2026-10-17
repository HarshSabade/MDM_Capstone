package mdm.capstone;

import java.util.List;

public final class LinearTrendPredictor implements RulPredictor, CapacityResidualProvider {
    private final int windowSize;

    public LinearTrendPredictor(int windowSize) {
        if (windowSize < 2) {
            throw new IllegalArgumentException("windowSize must be at least 2");
        }
        this.windowSize = windowSize;
    }

    @Override
    public String name() {
        return "linear";
    }

    @Override
    public RulPrediction predict(List<Observation> observed, double eolCapacityAh) {
        int window = Math.min(windowSize, observed.size());
        List<Observation> tail = observed.subList(observed.size() - window, observed.size());
        double meanX = tail.stream().mapToDouble(Observation::cycle).average().orElse(0.0);
        double meanY = tail.stream().mapToDouble(Observation::capacityAh).average().orElse(0.0);
        double numerator = 0.0;
        double denominator = 0.0;
        for (Observation value : tail) {
            numerator += (value.cycle() - meanX) * (value.capacityAh() - meanY);
            denominator += Math.pow(value.cycle() - meanX, 2);
        }
        double slope = denominator == 0.0 ? 0.0 : numerator / denominator;
        if (slope >= -1e-9) {
            int current = observed.get(observed.size() - 1).cycle();
            return new RulPrediction(name(), current, "non_decreasing_trend", Double.NaN,
                    new double[]{meanY, slope});
        }
        double intercept = meanY - slope * meanX;
        int eol = Math.max(observed.get(observed.size() - 1).cycle(),
                (int) Math.ceil((eolCapacityAh - intercept) / slope));
        return new RulPrediction(name(), eol, "ok", Double.NaN,
                new double[]{intercept, slope});
    }

    @Override
    public CapacityFit fittedCapacity(List<Observation> observed) {
        int window = Math.min(windowSize, observed.size());
        List<Observation> tail = observed.subList(observed.size() - window, observed.size());
        double meanX = tail.stream().mapToDouble(Observation::cycle).average().orElse(0.0);
        double meanY = tail.stream().mapToDouble(Observation::capacityAh).average().orElse(0.0);
        double numerator = 0.0;
        double denominator = 0.0;
        for (Observation value : tail) {
            numerator += (value.cycle() - meanX) * (value.capacityAh() - meanY);
            denominator += (value.cycle() - meanX) * (value.cycle() - meanX);
        }
        double slope = denominator == 0.0 ? 0.0 : numerator / denominator;
        double intercept = meanY - slope * meanX;
        double[] fitted = observed.stream()
                .mapToDouble(value -> intercept + slope * value.cycle()).toArray();
        double[] residuals = new double[window];
        for (int index = 0; index < window; index++) {
            Observation value = tail.get(index);
            residuals[index] = value.capacityAh() - (intercept + slope * value.cycle());
        }
        return new CapacityFit(fitted, residuals);
    }
}
