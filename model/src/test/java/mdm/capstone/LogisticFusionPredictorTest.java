package mdm.capstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LogisticFusionPredictorTest {
    @Test
    void trustsSimilarityEarlyAndTrendLater() {
        RulPredictor similarity = fixed("similarity", 100);
        RulPredictor trend = fixed("ssa", 20);
        List<Observation> observed = observations(10);

        LogisticFusionPredictor early = new LogisticFusionPredictor(
                similarity, trend, 0.2, 0.5, 10.0);
        LogisticFusionPredictor late = new LogisticFusionPredictor(
                similarity, trend, 0.8, 0.5, 10.0);

        assertTrue(early.similarityWeight() > 0.95);
        assertTrue(late.similarityWeight() < 0.05);
        assertTrue(early.predict(observed, 1.4).predictedRul(9) > 90);
        assertTrue(late.predict(observed, 1.4).predictedRul(9) < 30);
    }

    @Test
    void reportsComponentFailureInsteadOfScoringFallbackAsValid() {
        RulPredictor similarity = fixed("similarity", 100);
        RulPredictor failedTrend = new RulPredictor() {
            @Override
            public String name() {
                return "ssa";
            }

            @Override
            public RulPrediction predict(List<Observation> observed, double eol) {
                return new RulPrediction("ssa", 9, "eol_not_reached",
                        Double.NaN, new double[0]);
            }
        };

        RulPrediction result = new LogisticFusionPredictor(
                similarity, failedTrend, 0.5, 0.5, 10.0)
                .predict(observations(10), 1.4);

        assertEquals("component_failed:ssa:eol_not_reached", result.status());
    }

    private static RulPredictor fixed(String name, int rul) {
        return new RulPredictor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public RulPrediction predict(List<Observation> observed, double eolCapacityAh) {
                int cycle = observed.get(observed.size() - 1).cycle();
                return new RulPrediction(name, cycle + rul, "ok", Double.NaN, new double[0]);
            }
        };
    }

    private static List<Observation> observations(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new Observation("test", index, 2.0 - index * 0.01, 100))
                .toList();
    }
}
