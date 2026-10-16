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

/**
 * Leakage-safe RUL benchmark on the canonical capacity table.
 *
 * Usage:
 *   mvn -q exec:java -Dexec.mainClass=mdm.capstone.RulBaselineRunner \
 *     -Dexec.args="..\eda\capacity\canonical_capacity_rul.csv ..\model-output"
 */
public final class RulBaselineRunner {
    private static final double EOL_CAPACITY_AH = 1.4;
    private static final int MIN_OBSERVATIONS = 8;

    private RulBaselineRunner() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: RulBaselineRunner <canonical-csv> <output-directory>");
            System.exit(2);
        }
        List<Observation> all = read(Paths.get(args[0]));
        Map<String, List<Observation>> byBattery = group(all);
        Set<String> testBatteries = new HashSet<>();
        for (Map.Entry<String, List<Observation>> entry : byBattery.entrySet()) {
            if (entry.getValue().stream().anyMatch(o -> o.eolCycle != null)) {
                testBatteries.add(entry.getKey());
            }
        }

        List<Prediction> predictions = new ArrayList<>();
        for (String battery : testBatteries) {
            List<Observation> trajectory = new ArrayList<>(byBattery.get(battery));
            trajectory.sort(Comparator.comparingInt(Observation::cycle));
            int eolCycle = trajectory.stream()
                    .filter(o -> o.eolCycle != null)
                    .mapToInt(o -> o.eolCycle)
                    .min()
                    .orElseThrow();
            trajectory.removeIf(o -> o.cycle > eolCycle);
            for (double fraction : new double[]{0.3, 0.5, 0.7}) {
                int observedCount = Math.max(MIN_OBSERVATIONS,
                        Math.min(trajectory.size() - 1, (int) Math.floor(trajectory.size() * fraction)));
                List<Observation> observed = trajectory.subList(0, observedCount);
                Observation current = observed.get(observed.size() - 1);
                int actualRul = current.eolCycle - current.cycle;
                int linearEol = forecastLinearEol(observed);
                int similarityEol = forecastSimilarityEol(battery, observed, byBattery);
                predictions.add(new Prediction(battery, fraction, current.cycle, actualRul,
                        linearEol - current.cycle,
                        similarityEol - current.cycle));
            }
        }

        Path output = Paths.get(args[1]);
        Files.createDirectories(output);
        writePredictions(predictions, output.resolve("rul_predictions.csv"));
        writeMetrics(predictions, output.resolve("rul_metrics.csv"));
        System.out.println("Evaluated " + predictions.size() + " battery/prefix cases.");
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

    private static int forecastSimilarityEol(String targetBattery, List<Observation> observed,
            Map<String, List<Observation>> byBattery) {
        String nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, List<Observation>> candidate : byBattery.entrySet()) {
            if (candidate.getKey().equals(targetBattery)
                    || candidate.getValue().stream().noneMatch(o -> o.eolCycle != null)
                    || candidate.getValue().size() < observed.size()) {
                continue;
            }
            double distance = prefixDistance(observed, candidate.getValue());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate.getKey();
            }
        }
        if (nearest == null) {
            return forecastLinearEol(observed);
        }
        List<Observation> reference = byBattery.get(nearest);
        int referenceIndex = Math.min(observed.size() - 1, reference.size() - 1);
        int referenceRemaining = reference.get(referenceIndex).eolCycle - reference.get(referenceIndex).cycle;
        return observed.get(observed.size() - 1).cycle + referenceRemaining;
    }

    private static double prefixDistance(List<Observation> target, List<Observation> candidate) {
        int count = Math.min(target.size(), candidate.size());
        double targetStart = target.get(0).capacityAh;
        double candidateStart = candidate.get(0).capacityAh;
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            double targetDrop = (targetStart - target.get(i).capacityAh) / targetStart;
            double candidateDrop = (candidateStart - candidate.get(i).capacityAh) / candidateStart;
            sum += Math.pow(targetDrop - candidateDrop, 2);
        }
        return Math.sqrt(sum / count);
    }

    private static void writePredictions(List<Prediction> predictions, Path output)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("battery_id,observed_fraction,prediction_cycle,actual_rul,"
                    + "linear_rul,similarity_rul");
            writer.newLine();
            for (Prediction value : predictions) {
                writer.write(String.join(",", value.batteryId,
                        Double.toString(value.observedFraction),
                        Integer.toString(value.predictionCycle),
                        Integer.toString(value.actualRul),
                        Integer.toString(value.linearRul),
                        Integer.toString(value.similarityRul)));
                writer.newLine();
            }
        }
    }

    private static void writeMetrics(List<Prediction> predictions, Path output)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("observed_fraction,method,cases,mae,rmse,mape_percent");
            writer.newLine();
            for (double fraction : new double[]{0.3, 0.5, 0.7}) {
                writeMetric(writer, predictions, fraction, "linear");
                writeMetric(writer, predictions, fraction, "similarity");
            }
        }
    }

    private static void writeMetric(BufferedWriter writer, List<Prediction> predictions,
            double fraction, String method) throws IOException {
        List<Double> errors = new ArrayList<>();
        List<Double> percentageErrors = new ArrayList<>();
        for (Prediction prediction : predictions) {
            if (prediction.observedFraction != fraction) {
                continue;
            }
            int estimate = "linear".equals(method)
                    ? prediction.linearRul : prediction.similarityRul;
            double error = estimate - prediction.actualRul;
            errors.add(Math.abs(error));
            percentageErrors.add(Math.abs(error) / Math.max(1.0, prediction.actualRul) * 100.0);
        }
        double mae = errors.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double rmse = Math.sqrt(errors.stream().mapToDouble(value -> value * value)
                .average().orElse(Double.NaN));
        double mape = percentageErrors.stream().mapToDouble(Double::doubleValue)
                .average().orElse(Double.NaN);
        writer.write(String.join(",", Double.toString(fraction), method,
                Integer.toString(errors.size()), Double.toString(mae),
                Double.toString(rmse), Double.toString(mape)));
        writer.newLine();
    }

    private record Observation(String batteryId, int cycle, double capacityAh,
            Integer eolCycle) {
    }

    private record Prediction(String batteryId, double observedFraction, int predictionCycle,
            int actualRul, int linearRul, int similarityRul) {
    }
}
