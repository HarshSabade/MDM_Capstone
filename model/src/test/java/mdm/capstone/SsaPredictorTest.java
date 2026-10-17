package mdm.capstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SsaPredictorTest {
    @Test
    void usesRecurrentForecastToCrossEol() {
        List<Observation> observations = linearCapacity(30, 2);

        RulPrediction prediction = new SsaPredictor(0.4, 5, 20, 2)
                .predict(observations, 1.4);

        assertEquals("ssa", prediction.method());
        assertEquals("ok", prediction.status());
        assertTrue(prediction.predictedEolCycle() > observations.get(observations.size() - 1).cycle());
        assertTrue(prediction.predictedEolCycle() <= 10_058);
        assertEquals(4, prediction.parameters().length);
        assertEquals(12.0, prediction.parameters()[0]);
        assertEquals(2.0, prediction.parameters()[1]);
    }

    @Test
    void mapsForecastStepsUsingObservedCycleSpacing() {
        List<Observation> observations = linearCapacity(30, 3);

        RulPrediction prediction = new SsaPredictor(0.4, 5, 20, 2)
                .predict(observations, 1.4);

        int currentCycle = observations.get(observations.size() - 1).cycle();
        assertEquals(0, (prediction.predictedEolCycle() - currentCycle) % 3);
    }

    @Test
    void rejectsInvalidRequestedComponentCount() {
        List<Observation> observations = linearCapacity(12, 1);

        RulPrediction prediction = new SsaPredictor(0.4, 5, 20, 20)
                .predict(observations, 1.4);

        assertEquals("invalid_components", prediction.status());
        assertFalse(Double.isFinite(prediction.fitRmse()));
    }

    @Test
    void handlesEmptyAndShortSequencesExplicitly() {
        RulPrediction empty = new SsaPredictor().predict(List.of(), 1.4);
        assertEquals("insufficient_observations", empty.status());
        assertEquals(0, empty.predictedEolCycle());

        RulPrediction shortSequence = new SsaPredictor()
                .predict(linearCapacity(7, 1), 1.4);
        assertEquals("insufficient_observations", shortSequence.status());
    }

    private static List<Observation> linearCapacity(int count, int cycleGap) {
        List<Observation> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new Observation("synthetic", index * cycleGap,
                    2.0 - 0.01 * index, 300));
        }
        return result;
    }
}
