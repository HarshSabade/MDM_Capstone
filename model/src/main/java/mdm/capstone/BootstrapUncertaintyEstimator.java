package mdm.capstone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Residual bootstrap for RUL forecasts.
 *
 * <p>A linear fit is used only to estimate the observed-prefix residual
 * distribution. Every synthetic prefix is then passed through the requested
 * predictor again, so the interval includes refitting and forecast
 * variability. The point estimate remains the prediction from the original
 * observed prefix.</p>
 */
public final class BootstrapUncertaintyEstimator {
    private final int sampleCount;
    private final double confidenceLevel;
    private final long seed;

    public BootstrapUncertaintyEstimator(int sampleCount, double confidenceLevel, long seed) {
        if (sampleCount < 20 || !Double.isFinite(confidenceLevel)
                || confidenceLevel <= 0.0 || confidenceLevel >= 1.0) {
            throw new IllegalArgumentException("Invalid bootstrap configuration");
        }
        this.sampleCount = sampleCount;
        this.confidenceLevel = confidenceLevel;
        this.seed = seed;
    }

    public BootstrapInterval estimate(List<Observation> observed, RulPredictor predictor,
            double eolCapacityAh) {
        if (observed == null || observed.size() < 5) {
            return BootstrapInterval.unavailable(sampleCount, "insufficient_observations");
        }
        RulPrediction original = predictor.predict(observed, eolCapacityAh);
        if (!"ok".equals(original.status())) {
            return BootstrapInterval.unavailable(sampleCount,
                    "base_prediction_failed:" + original.status());
        }

        CapacityResidualProvider.CapacityFit modelFit = predictor instanceof CapacityResidualProvider provider
                ? provider.fittedCapacity(observed) : null;
        double[] residuals = modelFit == null ? null : modelFit.residuals();
        double[] fitted = modelFit == null ? null : modelFit.fitted();
        if (residuals == null || fitted == null || residuals.length == 0
                || fitted.length != observed.size()) {
            TrendFit fit = fitTrend(observed);
            residuals = fit == null ? null : fit.residuals();
            fitted = fit == null ? null : fit.fitted(observed);
        }
        if (residuals == null || fitted == null || residuals.length == 0) {
            return BootstrapInterval.unavailable(sampleCount, "residual_fit_failed");
        }
        Random random = new Random(seed);
        List<Double> rulValues = new ArrayList<>(sampleCount);
        for (int sample = 0; sample < sampleCount; sample++) {
            List<Observation> synthetic = resample(observed, fitted, residuals, random);
            RulPrediction prediction = predictor.predict(synthetic, eolCapacityAh);
            if ("ok".equals(prediction.status())) {
                int currentCycle = observed.get(observed.size() - 1).cycle();
                rulValues.add((double) prediction.predictedRul(currentCycle));
            }
        }
        if (rulValues.size() < Math.max(20, sampleCount / 10)) {
            return new BootstrapInterval(Double.NaN, Double.NaN, Double.NaN,
                    sampleCount, rulValues.size(), "too_few_successful_samples");
        }
        double[] values = rulValues.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(values);
        double alpha = (1.0 - confidenceLevel) / 2.0;
        return new BootstrapInterval(
                quantile(values, alpha),
                quantile(values, 0.5),
                quantile(values, 1.0 - alpha),
                sampleCount, values.length, "ok");
    }

    private static List<Observation> resample(List<Observation> observed, double[] fitted,
            double[] residuals, Random random) {
        List<Observation> synthetic = new ArrayList<>(observed.size());
        for (int index = 0; index < observed.size(); index++) {
            Observation value = observed.get(index);
            double residual = residuals[random.nextInt(residuals.length)];
            double capacity = fitted[index] + residual;
            synthetic.add(new Observation(value.batteryId(), value.cycle(), capacity,
                    value.eolCycle()));
        }
        return synthetic;
    }

    private static TrendFit fitTrend(List<Observation> observations) {
        double meanX = observations.stream().mapToDouble(Observation::cycle).average().orElse(0.0);
        double meanY = observations.stream().mapToDouble(Observation::capacityAh)
                .average().orElse(0.0);
        double numerator = 0.0;
        double denominator = 0.0;
        for (Observation value : observations) {
            numerator += (value.cycle() - meanX) * (value.capacityAh() - meanY);
            denominator += (value.cycle() - meanX) * (value.cycle() - meanX);
        }
        if (!Double.isFinite(denominator) || denominator <= 0.0) {
            return null;
        }
        double slope = numerator / denominator;
        double intercept = meanY - slope * meanX;
        double[] residuals = observations.stream()
                .mapToDouble(value -> value.capacityAh()
                        - (intercept + slope * value.cycle()))
                .toArray();
        return new TrendFit(intercept, slope, residuals);
    }

    private static double quantile(double[] sorted, double probability) {
        double position = probability * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = position - lower;
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight;
    }

    private record TrendFit(double intercept, double slope, double[] residuals) {
        private double[] fitted(List<Observation> observations) {
            return observations.stream().mapToDouble(o -> intercept + slope * o.cycle()).toArray();
        }
    }
}
