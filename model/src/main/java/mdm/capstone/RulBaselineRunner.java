package mdm.capstone;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Leave-one-battery-out, expanding-window RUL benchmark.
 *
 * The similarity predictor follows the proposed RMSE-plus-offset method:
 * the reference prefix is shifted to the target's latest capacity, then
 * unshifted reference prefixes are ranked by RMSE over the observed window.
 */
public final class RulBaselineRunner {
    private static final double EOL_CAPACITY_AH = 1.4;
    private static final int MIN_OBSERVATIONS = 8;
    private static final double[] OBSERVED_FRACTIONS =
            {0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8};

    private RulBaselineRunner() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: RulBaselineRunner <canonical-csv> <output-directory>");
            System.exit(2);
        }
        Map<String, List<Observation>> byBattery = group(read(Paths.get(args[0])));
        Set<String> testBatteries = new TreeSet<>();
        for (Map.Entry<String, List<Observation>> entry : byBattery.entrySet()) {
            if (entry.getValue().stream().anyMatch(o -> o.eolCycle != null)) {
                testBatteries.add(entry.getKey());
            }
        }

        List<Prediction> predictions = new ArrayList<>();
        for (String testBattery : testBatteries) {
            List<Observation> trajectory = observedTrajectory(byBattery.get(testBattery));
            for (double fraction : OBSERVED_FRACTIONS) {
                int observedCount = Math.max(MIN_OBSERVATIONS,
                        Math.min(trajectory.size() - 1,
                                (int) Math.floor(trajectory.size() * fraction)));
                if (observedCount >= trajectory.size()) {
                    continue;
                }
                List<Observation> observed = trajectory.subList(0, observedCount);
                Observation current = observed.get(observed.size() - 1);
                int actualRul = current.eolCycle - current.cycle;
                int linearRul = forecastLinearEol(observed) - current.cycle;
                SimilarityForecast similarity = forecastSimilarityEol(
                        testBattery, observed, byBattery);
                predictions.add(new Prediction(testBattery, fraction, current.cycle,
                        actualRul, linearRul,
                        similarity.eolCycle - current.cycle,
                        similarity.referenceBattery, similarity.rmse, similarity.offset));
            }
        }

        Path output = Paths.get(args[1]);
        Files.createDirectories(output);
        writePredictions(predictions, output.resolve("rul_predictions.csv"));
        writeMetrics(predictions, output.resolve("rul_metrics.csv"));
        System.out.println("Evaluated " + predictions.size()
                + " leave-one-battery-out expanding-window cases.");
        System.out.println("Wrote " + output.toAbsolutePath());
    }

    private static List<Observation> read(Path file) throws IOException {
        List<Observation> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!("battery_id,cycle,capacity_raw_ah,ambient_temperature_c,soh_raw,"
                    + "eol_cycle,rul_cycles,eol_observed").equals(header)) {
                throw new IOException("Unexpected canonical header: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                Integer eol = values[5].isBlank() ? null : Integer.valueOf(values[5]);
                result.add(new Observation(values[0], Integer.parseInt(values[1]),
                        Double.parseDouble(values[2]), eol));
            }
        }
        return result;
    }

    private static Map<String, List<Observation>> group(List<Observation> observations) {
        Map<String, List<Observation>> result = new HashMap<>();
        for (Observation observation : observations) {
            result.computeIfAbsent(observation.batteryId, ignored -> new ArrayList<>())
                    .add(observation);
        }
        return result;
    }

    private static List<Observation> observedTrajectory(List<Observation> source) {
        List<Observation> trajectory = new ArrayList<>(source);
        trajectory.sort(Comparator.comparingInt(Observation::cycle));
        int eolCycle = trajectory.stream()
                .filter(o -> o.eolCycle != null)
                .mapToInt(o -> o.eolCycle)
                .min()
                .orElseThrow();
        trajectory.removeIf(o -> o.cycle > eolCycle);
        return trajectory;
    }

    private static int forecastLinearEol(List<Observation> observed) {
        int window = Math.min(20, observed.size());
        List<Observation> tail = observed.subList(observed.size() - window, observed.size());
        double meanX = tail.stream().mapToDouble(o -> o.cycle).average().orElse(0.0);
        double meanY = tail.stream().mapToDouble(o -> o.capacityAh).average().orElse(0.0);
        double numerator = 0.0;
        double denominator = 0.0;
        for (Observation value : tail) {
            numerator += (value.cycle - meanX) * (value.capacityAh - meanY);
            denominator += Math.pow(value.cycle - meanX, 2);
        }
        double slope = denominator == 0.0 ? 0.0 : numerator / denominator;
        if (slope >= -1e-9) {
            return observed.get(observed.size() - 1).cycle;
        }
        double intercept = meanY - slope * meanX;
        return Math.max(observed.get(observed.size() - 1).cycle,
                (int) Math.ceil((EOL_CAPACITY_AH - intercept) / slope));
    }

    private static SimilarityForecast forecastSimilarityEol(String testBattery,
            List<Observation> observed, Map<String, List<Observation>> byBattery) {
        SimilarityForecast best = null;
        for (Map.Entry<String, List<Observation>> entry : byBattery.entrySet()) {
            if (entry.getKey().equals(testBattery)
                    || entry.getValue().stream().noneMatch(o -> o.eolCycle != null)) {
                continue;
            }
            List<Observation> reference = observedTrajectory(entry.getValue());
            if (reference.size() <= observed.size()) {
                continue;
            }
            SimilarityScore score = calculateSimilarity(observed, reference);
            if (best == null || score.rmse < best.rmse) {
                best = new SimilarityForecast(0, entry.getKey(), score.rmse, score.offset);
            }
        }
        if (best == null) {
            throw new IllegalStateException("No reference battery has enough observed data for "
                    + testBattery + " at prefix length " + observed.size());
        }

        List<Observation> reference = observedTrajectory(byBattery.get(best.referenceBattery));
        int referenceIndex = observed.size() - 1;
        Observation referenceCurrent = reference.get(referenceIndex);
        Observation targetCurrent = observed.get(observed.size() - 1);
        int predictedEolCycle = targetCurrent.cycle
                + (reference.get(reference.size() - 1).cycle - referenceCurrent.cycle);
        for (int i = referenceIndex + 1; i < reference.size(); i++) {
            double shiftedCapacity = reference.get(i).capacityAh + best.offset;
            if (shiftedCapacity <= EOL_CAPACITY_AH) {
                predictedEolCycle = targetCurrent.cycle
                        + (reference.get(i).cycle - referenceCurrent.cycle);
                break;
            }
        }
        return new SimilarityForecast(predictedEolCycle, best.referenceBattery,
                best.rmse, best.offset);
    }

    private static SimilarityScore calculateSimilarity(List<Observation> target,
            List<Observation> reference) {
        int count = target.size();
        double offset = target.get(count - 1).capacityAh
                - reference.get(count - 1).capacityAh;
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            double residual = target.get(i).capacityAh
                    - (reference.get(i).capacityAh + offset);
            sum += residual * residual;
        }
        return new SimilarityScore(Math.sqrt(sum / count), offset);
    }

    private static void writePredictions(List<Prediction> predictions, Path output)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("battery_id,observed_fraction,prediction_cycle,actual_rul,"
                    + "linear_rul,similarity_rul,reference_battery,similarity_rmse,"
                    + "similarity_offset_ah");
            writer.newLine();
            for (Prediction value : predictions) {
                writer.write(String.join(",", value.batteryId,
                        Double.toString(value.observedFraction),
                        Integer.toString(value.predictionCycle),
                        Integer.toString(value.actualRul),
                        Integer.toString(value.linearRul),
                        Integer.toString(value.similarityRul),
                        value.referenceBattery,
                        Double.toString(value.similarityRmse),
                        Double.toString(value.similarityOffset)));
                writer.newLine();
            }
        }
    }

    private static void writeMetrics(List<Prediction> predictions, Path output)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("observed_fraction,method,cases,mae,rmse,mape_percent");
            writer.newLine();
            for (double fraction : OBSERVED_FRACTIONS) {
                writeMetric(writer, predictions, fraction, "linear");
                writeMetric(writer, predictions, fraction, "similarity");
            }
        }
    }

    private static void writeMetric(BufferedWriter writer, List<Prediction> predictions,
            double fraction, String method) throws IOException {
        List<Double> absoluteErrors = new ArrayList<>();
        List<Double> percentageErrors = new ArrayList<>();
        for (Prediction prediction : predictions) {
            if (Double.compare(prediction.observedFraction, fraction) != 0) {
                continue;
            }
            int estimate = "linear".equals(method)
                    ? prediction.linearRul : prediction.similarityRul;
            double error = estimate - prediction.actualRul;
            absoluteErrors.add(Math.abs(error));
            percentageErrors.add(Math.abs(error)
                    / Math.max(1.0, prediction.actualRul) * 100.0);
        }
        double mae = absoluteErrors.stream().mapToDouble(Double::doubleValue)
                .average().orElse(Double.NaN);
        double rmse = Math.sqrt(absoluteErrors.stream()
                .mapToDouble(value -> value * value).average().orElse(Double.NaN));
        double mape = percentageErrors.stream().mapToDouble(Double::doubleValue)
                .average().orElse(Double.NaN);
        writer.write(String.join(",", Double.toString(fraction), method,
                Integer.toString(absoluteErrors.size()), Double.toString(mae),
                Double.toString(rmse), Double.toString(mape)));
        writer.newLine();
    }

    private record Observation(String batteryId, int cycle, double capacityAh,
            Integer eolCycle) {
    }

    private record SimilarityScore(double rmse, double offset) {
    }

    private record SimilarityForecast(int eolCycle, String referenceBattery,
            double rmse, double offset) {
    }

    private record Prediction(String batteryId, double observedFraction, int predictionCycle,
            int actualRul, int linearRul, int similarityRul, String referenceBattery,
            double similarityRmse, double similarityOffset) {
    }
}
