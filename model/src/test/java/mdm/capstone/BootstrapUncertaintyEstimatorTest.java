package mdm.capstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BootstrapUncertaintyEstimatorTest {
    @Test
    void producesReproducibleIntervalForSuccessfulPredictor() {
        List<Observation> observations = observations();
        BootstrapUncertaintyEstimator estimator =
                new BootstrapUncertaintyEstimator(50, 0.95, 1234L);

        BootstrapInterval first = estimator.estimate(observations,
                new LinearTrendPredictor(10), 1.4);
        BootstrapInterval second = estimator.estimate(observations,
                new LinearTrendPredictor(10), 1.4);

        assertEquals("ok", first.status());
        assertEquals(50, first.requestedSamples());
        assertTrue(first.successfulSamples() >= 20);
        assertTrue(first.lowerRul() <= first.medianRul());
        assertTrue(first.medianRul() <= first.upperRul());
        assertEquals(first.lowerRul(), second.lowerRul());
        assertEquals(first.upperRul(), second.upperRul());
    }

    @Test
    void doesNotBootstrapFailedBasePrediction() {
        RulPredictor failed = new RulPredictor() {
            @Override
            public String name() {
                return "failed";
            }

            @Override
            public RulPrediction predict(List<Observation> observed, double eolCapacityAh) {
                return new RulPrediction(name(), observed.get(observed.size() - 1).cycle(),
                        "eol_not_reached", Double.NaN, new double[0]);
            }
        };
        BootstrapInterval interval = new BootstrapUncertaintyEstimator(50, 0.95, 1234L)
                .estimate(observations(), failed, 1.4);

        assertTrue(interval.status().startsWith("base_prediction_failed:"));
        assertEquals(0, interval.successfulSamples());
    }

    private static List<Observation> observations() {
        List<Observation> result = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            double noise = index % 3 == 0 ? 0.01 : -0.01;
            result.add(new Observation("test", index, 2.0 - index * 0.02 + noise, 100));
        }
        return result;
    }
}
