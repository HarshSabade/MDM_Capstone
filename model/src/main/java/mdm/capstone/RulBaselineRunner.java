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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

public final class RulBaselineRunner {
    private static final double EOL_CAPACITY_AH = 1.4;
    private static final int MIN_OBSERVATIONS = 8;
    private static final int BOOTSTRAP_SAMPLES = 200;
    private static final double BOOTSTRAP_CONFIDENCE = 0.95;
    private static final long BOOTSTRAP_SEED = 20261017L;
    private static final double[] FRACTIONS = {0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8};

    private RulBaselineRunner() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: RulBaselineRunner <canonical-csv> <output-directory>");
            System.exit(2);
        }
        Map<String, List<Observation>> batteries = group(read(Paths.get(args[0])));
        BootstrapUncertaintyEstimator bootstrap = new BootstrapUncertaintyEstimator(
                BOOTSTRAP_SAMPLES, BOOTSTRAP_CONFIDENCE, BOOTSTRAP_SEED);
        List<EvaluationRow> rows = new ArrayList<>();
        List<PredictorProfile> profiles = new ArrayList<>();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        for (String testBattery : new TreeSet<>(batteries.keySet())) {
            List<Observation> trajectory = preEol(batteries.get(testBattery));
            if (trajectory.isEmpty() || trajectory.get(0).eolCycle() == null) {
                continue;
            }
            for (double fraction : FRACTIONS) {
                int count = Math.max(MIN_OBSERVATIONS,
                        Math.min(trajectory.size() - 1,
                                (int) Math.floor(trajectory.size() * fraction)));
                if (count >= trajectory.size()) {
                    continue;
                }
                List<Observation> observed = trajectory.subList(0, count);
                Observation current = observed.get(observed.size() - 1);
                int actualRul = current.eolCycle() - current.cycle();
                RulPredictor similarity = new SimilarityPredictor(testBattery, batteries);
                List<RulPredictor> predictors = List.of(
                        new LinearTrendPredictor(20),
                        similarity,
                        new DoubleExponentialPredictor(),
                        new SsaPredictor(),
                        new LogisticFusionPredictor(similarity, new DoubleExponentialPredictor(),
                                fraction, 0.5, 10.0),
                        new LogisticFusionPredictor(similarity, new SsaPredictor(),
                                fraction, 0.5, 10.0));
                for (RulPredictor predictor : predictors) {
                    long heapBefore = memory.getHeapMemoryUsage().getUsed();
                    long started = System.nanoTime();
                    RulPrediction result = predictor.predict(observed, EOL_CAPACITY_AH);
                    long elapsed = System.nanoTime() - started;
                    long heapAfter = memory.getHeapMemoryUsage().getUsed();
                    profiles.add(new PredictorProfile(testBattery, fraction, predictor.name(),
                            elapsed, heapBefore, heapAfter, result.status()));
                    BootstrapInterval interval = bootstrap.estimate(observed, predictor,
                            EOL_CAPACITY_AH);
                    rows.add(new EvaluationRow(testBattery, fraction, current.cycle(),
                            actualRul, result, interval));
                }
            }
        }
        Path output = Paths.get(args[1]);
        Files.createDirectories(output);
        writePredictions(rows, output.resolve("rul_predictions.csv"));
        writeMetrics(rows, output.resolve("rul_metrics.csv"));
        writeProfiles(profiles, output.resolve("predictor_profiles.csv"));
        System.out.println("Evaluated " + rows.size() + " predictor cases.");
    }

    private static List<Observation> read(Path file) throws IOException {
        List<Observation> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] v = line.split(",", -1);
                Integer eol = v[5].isBlank() ? null : Integer.valueOf(v[5]);
                result.add(new Observation(v[0], Integer.parseInt(v[1]),
                        Double.parseDouble(v[2]), eol));
            }
        }
        return result;
    }

    private static Map<String, List<Observation>> group(List<Observation> values) {
        Map<String, List<Observation>> result = new HashMap<>();
        for (Observation value : values) {
            result.computeIfAbsent(value.batteryId(), ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static List<Observation> preEol(List<Observation> source) {
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

    private static void writePredictions(List<EvaluationRow> rows, Path output)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("battery_id,observed_fraction,prediction_cycle,actual_rul,"
                    + "method,predicted_rul,predicted_eol_cycle,status,fit_rmse,"
                    + "parameter_0,parameter_1,parameter_2,parameter_3,parameter_4,"
                    + "rul_lower_bound,rul_median_bootstrap,rul_upper_bound,"
                    + "bootstrap_requested,bootstrap_successful,bootstrap_status");
            writer.newLine();
            for (EvaluationRow row : rows) {
                double[] p = row.result.parameters();
                writer.write(String.join(",", row.batteryId,
                        Double.toString(row.fraction), Integer.toString(row.predictionCycle),
                        Integer.toString(row.actualRul), row.result.method(),
                        Integer.toString(row.result.predictedRul(row.predictionCycle)),
                        Integer.toString(row.result.predictedEolCycle()), row.result.status(),
                        Double.toString(row.result.fitRmse()), parameter(p, 0), parameter(p, 1),
                        parameter(p, 2), parameter(p, 3), parameter(p, 4),
                        Double.toString(row.interval.lowerRul()),
                        Double.toString(row.interval.medianRul()),
                        Double.toString(row.interval.upperRul()),
                        Integer.toString(row.interval.requestedSamples()),
                        Integer.toString(row.interval.successfulSamples()),
                        row.interval.status()));
                writer.newLine();
            }
        }
    }

    private static void writeMetrics(List<EvaluationRow> rows, Path output)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("observed_fraction,method,cases,successful_cases,mae,rmse,mape_percent,"
                    + "coverage_percent,mean_interval_width,median_interval_width");
            writer.newLine();
            List<String> methods = List.of("linear", "similarity", "double_exponential", "ssa",
                    "fused_double_exponential", "fused_ssa");
            for (double fraction : FRACTIONS) {
                for (String method : methods) {
                    List<EvaluationRow> selected = rows.stream()
                            .filter(r -> Double.compare(r.fraction, fraction) == 0)
                            .filter(r -> r.result.method().equals(method)).toList();
                    if (selected.isEmpty()) {
                        continue;
                    }

                    List<Double> errors = selected.stream()
                            .filter(r -> r.result.status().equals("ok"))
                            .map(r -> (double) (r.result.predictedRul(r.predictionCycle)
                                    - r.actualRul)).toList();
                    List<Double> percentageErrors = selected.stream()
                            .filter(r -> r.result.status().equals("ok"))
                            .map(r -> Math.abs((double) (r.result.predictedRul(r.predictionCycle)
                                    - r.actualRul)) * 100.0 / Math.max(1, r.actualRul)).toList();
                    double mae = errors.stream().mapToDouble(Math::abs).average().orElse(Double.NaN);
                    double rmse = Math.sqrt(errors.stream().mapToDouble(e -> e * e)
                            .average().orElse(Double.NaN));
                    double mape = percentageErrors.stream().mapToDouble(Double::doubleValue)
                            .average().orElse(Double.NaN);
                    List<Double> widths = selected.stream()
                            .filter(r -> "ok".equals(r.result.status()))
                            .filter(r -> "ok".equals(r.interval.status()))
                            .map(r -> r.interval.upperRul() - r.interval.lowerRul()).toList();
                    List<Boolean> covered = selected.stream()
                            .filter(r -> "ok".equals(r.result.status()))
                            .filter(r -> "ok".equals(r.interval.status()))
                            .map(r -> r.interval.lowerRul() <= r.actualRul
                                    && r.actualRul <= r.interval.upperRul()).toList();
                    double coverage = covered.stream().mapToInt(value -> value ? 1 : 0)
                            .average().orElse(Double.NaN) * 100.0;
                    double meanWidth = widths.stream().mapToDouble(Double::doubleValue)
                            .average().orElse(Double.NaN);
                    double medianWidth = widths.isEmpty() ? Double.NaN
                            : widths.stream().sorted().skip((widths.size() - 1) / 2)
                                    .findFirst().orElse(Double.NaN);
                    writer.write(String.join(",", Double.toString(fraction), method,
                            Integer.toString(selected.size()), Integer.toString(errors.size()),
                            Double.toString(mae), Double.toString(rmse), Double.toString(mape),
                            Double.toString(coverage), Double.toString(meanWidth),
                            Double.toString(medianWidth)));
                    writer.newLine();
                }
            }

        }
    }

    private static void writeProfiles(List<PredictorProfile> profiles, Path output)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("battery_id,observed_fraction,method,elapsed_nanos,elapsed_millis,"
                    + "heap_before_bytes,heap_after_bytes,heap_delta_bytes,status");
            writer.newLine();
            for (PredictorProfile profile : profiles) {
                writer.write(String.join(",", profile.batteryId(),
                        Double.toString(profile.observedFraction()), profile.method(),
                        Long.toString(profile.elapsedNanos()),
                        Long.toString(profile.elapsedMillis()),
                        Long.toString(profile.heapBeforeBytes()),
                        Long.toString(profile.heapAfterBytes()),
                        Long.toString(profile.heapDeltaBytes()), profile.status()));
                writer.newLine();
            }
        }
    }

    private static String parameter(double[] parameters, int index) {
        return parameters.length > index ? Double.toString(parameters[index]) : "";
    }

    private record EvaluationRow(String batteryId, double fraction, int predictionCycle,
            int actualRul, RulPrediction result, BootstrapInterval interval) {
    }
}
