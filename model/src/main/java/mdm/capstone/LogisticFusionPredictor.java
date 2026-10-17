package mdm.capstone;

import java.util.List;

/**
 * Fuses a similarity forecast with a trend forecast using a stage-dependent
 * logistic weight. The supplied life fraction is the observed chronological
 * prefix fraction, not a value derived from the hidden test EOL.
 */
public final class LogisticFusionPredictor implements RulPredictor, CapacityResidualProvider {
    private final RulPredictor similarityPredictor;
    private final RulPredictor trendPredictor;
    private final double observedLifeFraction;
    private final double inflection;
    private final double steepness;
    private final String methodName;

    public LogisticFusionPredictor(RulPredictor similarityPredictor,
            RulPredictor trendPredictor, double observedLifeFraction,
            double inflection, double steepness) {
        if (similarityPredictor == null || trendPredictor == null
                || !Double.isFinite(observedLifeFraction)
                || observedLifeFraction < 0.0 || observedLifeFraction > 1.0
                || !Double.isFinite(inflection) || !Double.isFinite(steepness)
                || steepness <= 0.0) {
            throw new IllegalArgumentException("Invalid logistic fusion configuration");
        }
        this.similarityPredictor = similarityPredictor;
        this.trendPredictor = trendPredictor;
        this.observedLifeFraction = observedLifeFraction;
        this.inflection = inflection;
        this.steepness = steepness;
        this.methodName = "fused_" + trendPredictor.name();
    }

    @Override
    public String name() {
        return methodName;
    }

    @Override
    public RulPrediction predict(List<Observation> observed, double eolCapacityAh) {
        if (observed == null || observed.isEmpty()) {
            return new RulPrediction(name(), 0, "insufficient_observations",
                    Double.NaN, new double[0]);
        }
        RulPrediction similarity = similarityPredictor.predict(observed, eolCapacityAh);
        RulPrediction trend = trendPredictor.predict(observed, eolCapacityAh);
        boolean similarityOk = "ok".equals(similarity.status());
        boolean trendOk = "ok".equals(trend.status());
        if (!similarityOk || !trendOk) {
            String failed = !similarityOk
                    ? similarityPredictor.name() + ":" + similarity.status()
                    : trendPredictor.name() + ":" + trend.status();
            return new RulPrediction(name(), currentCycle(observed),
                    "component_failed:" + failed, Double.NaN, new double[0]);
        }

        int currentCycle = currentCycle(observed);
        int similarityRul = similarity.predictedRul(currentCycle);
        int trendRul = trend.predictedRul(currentCycle);
        double similarityWeight = similarityWeight(observedLifeFraction);
        int fusedRul = (int) Math.max(0,
                Math.round(similarityWeight * similarityRul
                        + (1.0 - similarityWeight) * trendRul));
        return new RulPrediction(name(), currentCycle + fusedRul, "ok",
                combinedFitRmse(similarity, trend),
                new double[] {similarityWeight, similarityRul, trendRul,
                        similarity.predictedEolCycle(), trend.predictedEolCycle()});
    }

    @Override
    public CapacityFit fittedCapacity(List<Observation> observed) {
        if (trendPredictor instanceof CapacityResidualProvider provider) {
            return provider.fittedCapacity(observed);
        }
        return null;
    }

    double similarityWeight() {
        return similarityWeight(observedLifeFraction);
    }

    private double similarityWeight(double lifeFraction) {
        double exponent = steepness * (lifeFraction - inflection);
        if (exponent >= 700.0) {
            return 0.0;
        }
        if (exponent <= -700.0) {
            return 1.0;
        }
        return 1.0 / (1.0 + Math.exp(exponent));
    }

    private static double combinedFitRmse(RulPrediction similarity, RulPrediction trend) {
        if (Double.isFinite(similarity.fitRmse()) && Double.isFinite(trend.fitRmse())) {
            return Math.sqrt((similarity.fitRmse() * similarity.fitRmse()
                    + trend.fitRmse() * trend.fitRmse()) / 2.0);
        }
        if (Double.isFinite(similarity.fitRmse())) {
            return similarity.fitRmse();
        }
        return trend.fitRmse();
    }

    private static int currentCycle(List<Observation> observed) {
        return observed.stream().mapToInt(Observation::cycle).max().orElse(0);
    }
}
