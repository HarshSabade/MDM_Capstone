package mdm.capstone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SimilarityPredictor implements RulPredictor {
    private final String targetBattery;
    private final Map<String, List<Observation>> references;

    public SimilarityPredictor(String targetBattery, Map<String, List<Observation>> references) {
        this.targetBattery = targetBattery;
        this.references = references;
    }

    @Override
    public String name() {
        return "similarity";
    }

    @Override
    public RulPrediction predict(List<Observation> observed, double eolCapacityAh) {
        Candidate best = null;
        for (Map.Entry<String, List<Observation>> entry : references.entrySet()) {
            if (entry.getKey().equals(targetBattery)) {
                continue;
            }
            List<Observation> reference = trajectory(entry.getValue());
            if (reference.isEmpty()) {
                continue;
            }
            if (reference.size() <= observed.size()) {
                continue;
            }
            Score score = score(observed, reference);
            if (best == null || score.rmse < best.rmse) {
                best = new Candidate(entry.getKey(), reference, score.rmse, score.offset);
            }
        }
        if (best == null) {
            int current = observed.get(observed.size() - 1).cycle();
            return new RulPrediction(name(), current, "no_valid_reference",
                    Double.NaN, new double[0]);
        }

        Observation targetCurrent = observed.get(observed.size() - 1);
        Observation referenceCurrent = best.reference.get(observed.size() - 1);
        int predictedEol = targetCurrent.cycle()
                + best.reference.get(best.reference.size() - 1).cycle()
                - referenceCurrent.cycle();
        for (int i = observed.size(); i < best.reference.size(); i++) {
            if (best.reference.get(i).capacityAh() + best.offset <= eolCapacityAh) {
                predictedEol = targetCurrent.cycle()
                        + best.reference.get(i).cycle() - referenceCurrent.cycle();
                break;
            }
        }
        return new RulPrediction(name(), predictedEol, "ok", best.rmse,
                new double[]{best.offset});
    }

    public String selectedReference(List<Observation> observed) {
        Candidate best = select(observed);
        return best == null ? "" : best.batteryId;
    }

    private Candidate select(List<Observation> observed) {
        Candidate best = null;
        for (Map.Entry<String, List<Observation>> entry : references.entrySet()) {
            if (entry.getKey().equals(targetBattery)) {
                continue;
            }
            List<Observation> reference = trajectory(entry.getValue());
            if (reference.size() <= observed.size()) {
                continue;
            }
            Score score = score(observed, reference);
            if (best == null || score.rmse < best.rmse) {
                best = new Candidate(entry.getKey(), reference, score.rmse, score.offset);
            }
        }
        return best;
    }

    private static List<Observation> trajectory(List<Observation> source) {
        List<Observation> result = new ArrayList<>(source);
        result.sort(Comparator.comparingInt(Observation::cycle));
        Integer eol = result.stream().filter(o -> o.eolCycle() != null)
                .map(Observation::eolCycle).min(Integer::compareTo).orElse(null);
        if (eol == null) {
            return List.of();
        }
        result.removeIf(o -> o.cycle() > eol);
        return result;
    }

    private static Score score(List<Observation> target, List<Observation> reference) {
        int count = target.size();
        double offset = target.get(count - 1).capacityAh()
                - reference.get(count - 1).capacityAh();
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            double error = target.get(i).capacityAh()
                    - reference.get(i).capacityAh() - offset;
            sum += error * error;
        }
        return new Score(Math.sqrt(sum / count), offset);
    }

    private record Score(double rmse, double offset) {
    }

    private record Candidate(String batteryId, List<Observation> reference,
            double rmse, double offset) {
    }
}
