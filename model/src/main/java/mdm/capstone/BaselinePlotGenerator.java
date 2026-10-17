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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * Generates SVG plots from the baseline CSV outputs.
 *
 * Usage:
 *   java ...BaselinePlotGenerator predictions.csv metrics.csv output-directory
 */
public final class BaselinePlotGenerator {
    private static final int WIDTH = 1100;
    private static final int HEIGHT = 650;
    private static final int LEFT = 85;
    private static final int RIGHT = 35;
    private static final int TOP = 60;
    private static final int BOTTOM = 125;
    private static final String[] COLORS = {"#1565c0", "#ef6c00", "#2e7d32", "#8e24aa",
        "#c62828", "#00838f"};

    private BaselinePlotGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: BaselinePlotGenerator <predictions.csv> <metrics.csv> <output-dir>");
            System.exit(2);
        }
        List<Prediction> predictions = readPredictions(Paths.get(args[0]));
        List<IntervalPrediction> intervals = readIntervals(Paths.get(args[0]));
        List<Metric> metrics = readMetrics(Paths.get(args[1]));
        Path output = Paths.get(args[2]);
        Files.createDirectories(output);

        writePredictionPlot(predictions, output.resolve("rul_actual_vs_predicted.svg"));
        writeErrorPlot(predictions, output.resolve("rul_absolute_error.svg"));
        writeIntervalPlot(intervals, output.resolve("rul_prediction_intervals.svg"));
        writeMetricPlot(metrics, "mae", "MAE (cycles)",
                output.resolve("baseline_mae_comparison.svg"));
        writeMetricPlot(metrics, "rmse", "RMSE (cycles)",
                output.resolve("baseline_rmse_comparison.svg"));
        writeMetricPlot(metrics, "mape_percent", "MAPE (%)",
                output.resolve("baseline_mape_comparison.svg"));
        System.out.println("Wrote plots to " + output.toAbsolutePath());
    }

    private static List<IntervalPrediction> readIntervals(Path file) throws IOException {
        List<IntervalPrediction> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] v = line.split(",", -1);
                if (v.length < 21 || !"ok".equals(v[7]) || !"ok".equals(v[20])) {
                    continue;
                }
                result.add(new IntervalPrediction(v[0], Double.parseDouble(v[1]),
                        Integer.parseInt(v[3]), v[4], Integer.parseInt(v[5]),
                        Double.parseDouble(v[15]), Double.parseDouble(v[16]),
                        Double.parseDouble(v[17])));
            }
        }
        return result;
    }

    private static void writeIntervalPlot(List<IntervalPrediction> values, Path output)
            throws IOException {
        double max = values.stream().mapToDouble(v -> Math.max(v.actualRul, v.upper))
                .max().orElse(1.0);
        writeChartStart(output, "Bootstrap RUL prediction intervals",
                "Prediction case", "RUL (cycles)", 0, values.size(), 0, max * 1.1, false);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)) {
            for (int index = 0; index < values.size(); index++) {
                IntervalPrediction value = values.get(index);
                double center = LEFT + (index + 0.5) * (WIDTH - LEFT - RIGHT)
                        / Math.max(1, values.size());
                double lower = y(value.lower, 0, max * 1.1);
                double upper = y(value.upper, 0, max * 1.1);
                double median = y(value.median, 0, max * 1.1);
                writer.write("<line x1=\"" + format(center) + "\" y1=\"" + format(lower)
                        + "\" x2=\"" + format(center) + "\" y2=\"" + format(upper)
                        + "\" stroke=\"#546e7a\" stroke-width=\"3\"/>");
                writer.write("<circle cx=\"" + format(center) + "\" cy=\""
                        + format(median) + "\" r=\"4\" fill=\"#c62828\"/>");
                writer.write("<circle cx=\"" + format(center + 5) + "\" cy=\""
                        + format(y(value.actualRul, 0, max * 1.1))
                        + "\" r=\"4\" fill=\"#1565c0\"/>");
            }
            writer.write("<text x=\"900\" y=\"85\" fill=\"#c62828\" font-size=\"14\">Bootstrap median</text>\n");
            writer.write("<text x=\"900\" y=\"109\" fill=\"#1565c0\" font-size=\"14\">Actual RUL</text>\n");
            writer.write("<text x=\"900\" y=\"133\" fill=\"#546e7a\" font-size=\"14\">95% interval</text>\n");
            writeChartEnd(writer);
        }
    }

    private static List<Prediction> readPredictions(Path file) throws IOException {
        Map<String, PredictionBuilder> grouped = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] v = line.split(",", -1);
                String key = v[0] + "|" + v[1] + "|" + v[2];
                PredictionBuilder builder = grouped.computeIfAbsent(key,
                        ignored -> new PredictionBuilder(v[0], Double.parseDouble(v[1]),
                                Integer.parseInt(v[2]), Integer.parseInt(v[3])));
                int predicted = Integer.parseInt(v[5]);
                if ("linear".equals(v[4])) {
                    builder.linearRul = predicted;
                } else if ("similarity".equals(v[4])) {
                    builder.similarityRul = predicted;
                }
            }
        }
        return grouped.values().stream()
                .filter(v -> v.linearRul != null && v.similarityRul != null)
                .map(PredictionBuilder::build).toList();
    }

    private static List<Metric> readMetrics(Path file) throws IOException {
        List<Metric> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] v = line.split(",", -1);
                result.add(new Metric(Double.parseDouble(v[0]), v[1],
                        Integer.parseInt(v[2]), Double.parseDouble(v[4]),
                        Double.parseDouble(v[5]), Double.parseDouble(v[6])));
            }
        }
        return result;
    }

    private static void writePredictionPlot(List<Prediction> values, Path output)
            throws IOException {
        double max = values.stream().mapToDouble(v -> Math.max(v.actualRul,
                Math.max(v.linearRul, v.similarityRul))).max().orElse(1);
        double min = values.stream().mapToDouble(v -> Math.min(v.actualRul,
                Math.min(v.linearRul, v.similarityRul))).min().orElse(0);
        double lower = Math.min(0, min);
        double upper = Math.max(1, max);
        writeChartStart(output, "Actual versus predicted RUL", "Actual RUL (cycles)",
                "Predicted RUL (cycles)", lower, upper, lower, upper);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)) {
            String[] methods = {"linear", "similarity"};
            for (int methodIndex = 0; methodIndex < methods.length; methodIndex++) {
                String method = methods[methodIndex];
                String color = COLORS[methodIndex];
                for (Prediction value : values) {
                    int predicted = "linear".equals(method) ? value.linearRul : value.similarityRul;
                    writer.write(point(value.actualRul, predicted, lower, upper, color));
                }
                writer.write("<text x=\"900\" y=\"" + (85 + methodIndex * 24)
                        + "\" fill=\"" + color + "\" font-size=\"14\">"
                        + title(method) + "</text>\n");
            }
            writer.write("<line x1=\"" + x(lower, lower, upper) + "\" y1=\""
                    + y(upper, lower, upper) + "\" x2=\"" + x(upper, lower, upper)
                    + "\" y2=\"" + y(lower, lower, upper)
                    + "\" stroke=\"#777\" stroke-dasharray=\"6,4\"/>\n");
            writeChartEnd(writer);
        }
    }

    private static void writeErrorPlot(List<Prediction> values, Path output) throws IOException {
        double maxError = values.stream().mapToDouble(v -> Math.max(
                Math.abs(v.linearRul - v.actualRul),
                Math.abs(v.similarityRul - v.actualRul))).max().orElse(1);
        writeChartStart(output, "Absolute RUL prediction error",
                "Battery and observed fraction", "Absolute error (cycles)",
                0, values.size(), 0, maxError, false);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)) {
            for (int index = 0; index < values.size(); index++) {
                Prediction value = values.get(index);
                double linearError = Math.abs(value.linearRul - value.actualRul);
                double similarityError = Math.abs(value.similarityRul - value.actualRul);
                writer.write(groupedBar(index, linearError, similarityError, maxError,
                        value.batteryId + " " + fractionLabel(value.fraction)));
            }
            writer.write("<text x=\"900\" y=\"85\" fill=\"" + COLORS[0]
                    + "\" font-size=\"14\">Linear</text>\n");
            writer.write("<text x=\"900\" y=\"109\" fill=\"" + COLORS[1]
                    + "\" font-size=\"14\">Similarity</text>\n");
            writeChartEnd(writer);
        }
    }

    private static String groupedBar(int index, double linearError, double similarityError,
            double max, String label) {
        double groupWidth = (WIDTH - LEFT - RIGHT) / 9.0;
        double center = LEFT + (index + 0.5) * groupWidth;
        double barWidth = Math.min(24.0, groupWidth * 0.28);
        double linearX = center - barWidth - 2;
        double similarityX = center + 2;
        StringBuilder result = new StringBuilder();
        result.append(rectangle(linearX, barWidth, linearError, max, COLORS[0]));
        result.append(rectangle(similarityX, barWidth, similarityError, max, COLORS[1]));
        result.append("<text x=\"").append(format(center)).append("\" y=\"")
                .append(HEIGHT - BOTTOM + 26)
                .append("\" text-anchor=\"end\" font-size=\"11\" transform=\"rotate(-45 ")
                .append(format(center)).append(" ").append(HEIGHT - BOTTOM + 26)
                .append(")\">").append(label).append("</text>\n");
        return result.toString();
    }

    private static String rectangle(double x, double width, double value, double max,
            String color) {
        double y = y(value, 0, max);
        return "<rect x=\"" + format(x) + "\" y=\"" + format(y) + "\" width=\""
                + format(width) + "\" height=\"" + format((HEIGHT - BOTTOM) - y)
                + "\" fill=\"" + color + "\"/>\n";
    }

    private static String fractionLabel(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100);
    }

    private static void writeMetricPlot(List<Metric> values, String metric, String yLabel,
            Path output) throws IOException {
        double max = values.stream().mapToDouble(v -> metric(v, metric)).max().orElse(1);
        writeChartStart(output, title(metric) + " baseline comparison",
                "Observed trajectory fraction", yLabel, 0.2, 0.8, 0, max * 1.15, true);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)) {
            String[] methods = {"linear", "similarity", "double_exponential", "ssa",
                "fused_double_exponential", "fused_ssa"};
            for (Metric value : values) {
                int methodIndex = methodIndex(value.method, methods);
                if (methodIndex < 0) {
                    continue;
                }
                double x = value.fraction + (methodIndex - 2.5) * 0.012;
                writer.write(barAt(x, metric(value, metric), 0.011, max * 1.15,
                        COLORS[methodIndex]));
            }
            for (int index = 0; index < methods.length; index++) {
                writer.write("<text x=\"900\" y=\"" + (85 + index * 22)
                        + "\" fill=\"" + COLORS[index] + "\" font-size=\"14\">"
                        + title(methods[index]) + "</text>\n");
            }
            writeChartEnd(writer);
        }
    }

    private static int methodIndex(String method, String[] methods) {
        for (int index = 0; index < methods.length; index++) {
            if (methods[index].equals(method)) {
                return index;
            }
        }
        return -1;
    }

    private static double metric(Metric value, String metric) {
        return switch (metric) {
            case "mae" -> value.mae;
            case "rmse" -> value.rmse;
            case "mape_percent" -> value.mape;
            default -> throw new IllegalArgumentException("Unknown metric: " + metric);
        };
    }

    private static void writeChartStart(Path output, String title, String xLabel, String yLabel,
            double xMin, double xMax, double yMin, double yMax) throws IOException {
        writeChartStart(output, title, xLabel, yLabel, xMin, xMax, yMin, yMax, true);
    }

    private static void writeChartStart(Path output, String title, String xLabel, String yLabel,
            double xMin, double xMax, double yMin, double yMax, boolean showXTickLabels)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + WIDTH
                    + "\" height=\"" + HEIGHT + "\">\n");
            writer.write("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
            writer.write("<text x=\"550\" y=\"30\" text-anchor=\"middle\" font-size=\"20\">"
                    + title + "</text>\n");
            writer.write("<line x1=\"" + LEFT + "\" y1=\"" + (HEIGHT - BOTTOM)
                    + "\" x2=\"" + (WIDTH - RIGHT) + "\" y2=\"" + (HEIGHT - BOTTOM)
                    + "\" stroke=\"black\"/><line x1=\"" + LEFT + "\" y1=\"" + TOP
                    + "\" x2=\"" + LEFT + "\" y2=\"" + (HEIGHT - BOTTOM)
                    + "\" stroke=\"black\"/>\n");
            writer.write("<text x=\"550\" y=\"640\" text-anchor=\"middle\">" + xLabel + "</text>\n");
            writer.write("<text transform=\"translate(18 360) rotate(-90)\" "
                    + "text-anchor=\"middle\">" + yLabel + "</text>\n");
            writeTicks(writer, xMin, xMax, yMin, yMax, showXTickLabels);
        }
    }

    private static void writeTicks(BufferedWriter writer, double xMin, double xMax,
            double yMin, double yMax, boolean showXTickLabels) throws IOException {
        int ticks = 6;
        for (int i = 0; i <= ticks; i++) {
            double fraction = (double) i / ticks;
            double x = LEFT + fraction * (WIDTH - LEFT - RIGHT);
            double y = HEIGHT - BOTTOM - fraction * (HEIGHT - TOP - BOTTOM);
            writer.write("<line x1=\"" + format(x) + "\" y1=\"" + TOP
                    + "\" x2=\"" + format(x) + "\" y2=\"" + (HEIGHT - BOTTOM)
                    + "\" stroke=\"#e0e0e0\"/>\n");
            writer.write("<line x1=\"" + LEFT + "\" y1=\"" + format(y)
                    + "\" x2=\"" + (WIDTH - RIGHT) + "\" y2=\"" + format(y)
                    + "\" stroke=\"#e0e0e0\"/>\n");
            if (showXTickLabels) {
                writer.write("<text x=\"" + format(x) + "\" y=\"" + (HEIGHT - BOTTOM + 22)
                        + "\" text-anchor=\"middle\" font-size=\"12\">"
                        + format(xMin + fraction * (xMax - xMin)) + "</text>\n");
            }
            writer.write("<text x=\"" + (LEFT - 10) + "\" y=\"" + format(y + 4)
                    + "\" text-anchor=\"end\" font-size=\"12\">"
                    + format(yMin + fraction * (yMax - yMin)) + "</text>\n");
        }
    }

    private static String point(double actual, double predicted, double min, double max,
            String color) {
        return "<circle cx=\"" + x(actual, min, max) + "\" cy=\"" + y(predicted, min, max)
                + "\" r=\"5\" fill=\"" + color + "\"/>\n";
    }

    private static String bar(int index, double value, double max, String color, String label) {
        double barWidth = (WIDTH - LEFT - RIGHT) / 18.0;
        double x = LEFT + index * barWidth + 5;
        double y = y(value, 0, max);
        return "<rect x=\"" + x + "\" y=\"" + y + "\" width=\"" + (barWidth - 10)
                + "\" height=\"" + ((HEIGHT - BOTTOM) - y) + "\" fill=\"" + color
                + "\"/><text x=\"" + x + "\" y=\"" + (HEIGHT - BOTTOM + 18)
                + "\" font-size=\"9\">" + label + "</text>\n";
    }

    private static String barAt(double value, double height, double width, double max,
            String color) {
        double x = x(value, 0.2, 0.8);
        double pixelWidth = width / 0.6 * (WIDTH - LEFT - RIGHT);
        double y = y(height, 0, max);
        return "<rect x=\"" + (x - pixelWidth / 2) + "\" y=\"" + y + "\" width=\""
                + pixelWidth + "\" height=\"" + ((HEIGHT - BOTTOM) - y)
                + "\" fill=\"" + color + "\"/>\n";
    }

    private static double x(double value, double min, double max) {
        return LEFT + (value - min) * (WIDTH - LEFT - RIGHT) / (max - min);
    }

    private static double y(double value, double min, double max) {
        return HEIGHT - BOTTOM - (value - min) * (HEIGHT - TOP - BOTTOM) / (max - min);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String title(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static void writeChartEnd(BufferedWriter writer) throws IOException {
        writer.write("</svg>\n");
    }

    private record Prediction(String batteryId, double fraction, int cycle, int actualRul,
            int linearRul, int similarityRul) {
    }

    private static final class PredictionBuilder {
        private final String batteryId;
        private final double fraction;
        private final int cycle;
        private final int actualRul;
        private Integer linearRul;
        private Integer similarityRul;

        private PredictionBuilder(String batteryId, double fraction, int cycle, int actualRul) {
            this.batteryId = batteryId;
            this.fraction = fraction;
            this.cycle = cycle;
            this.actualRul = actualRul;
        }

        private Prediction build() {
            return new Prediction(batteryId, fraction, cycle, actualRul,
                    linearRul, similarityRul);
        }
    }

    private record Metric(double fraction, String method, int cases, double mae,
            double rmse, double mape) {
    }

    private record IntervalPrediction(String batteryId, double fraction, int actualRul,
            String method, int predictedRul, double lower, double median, double upper) {
    }
}
