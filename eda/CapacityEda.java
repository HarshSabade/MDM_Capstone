import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the canonical capacity/RUL table and lightweight SVG EDA plots.
 *
 * Usage:
 *   java CapacityEda "path\to\FY08Q4" "path\to\eda-output"
 */
public final class CapacityEda {
    private static final double NOMINAL_CAPACITY_AH = 2.0;
    private static final double EOL_CAPACITY_AH = 1.4;
    private static final int PLOT_WIDTH = 1100;
    private static final int PLOT_HEIGHT = 650;
    private static final int LEFT = 80;
    private static final int RIGHT = 30;
    private static final int TOP = 50;
    private static final int BOTTOM = 75;

    private CapacityEda() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java CapacityEda <csv-directory> <output-directory>");
            System.exit(2);
        }
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        Files.createDirectories(output);

        List<Record> records = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(input, "*_capacity.csv")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String batteryId = name.substring(0, name.length() - "_capacity.csv".length());
                records.addAll(read(file, batteryId));
            }
        }
        records.sort(Comparator.comparing(Record::batteryId).thenComparingInt(Record::cycle));
        Map<String, Integer> eolByBattery = findEolCycles(records);

        Path canonical = output.resolve("canonical_capacity_rul.csv");
        writeCanonical(records, eolByBattery, canonical);
        Path summary = output.resolve("capacity_summary.csv");
        writeSummary(records, eolByBattery, summary);
        writeSvg(records, eolByBattery, output.resolve("capacity_soh_trajectories.svg"));

        System.out.println("Wrote " + canonical.toAbsolutePath());
        System.out.println("Wrote " + summary.toAbsolutePath());
        System.out.println("Wrote " + output.resolve("capacity_soh_trajectories.svg").toAbsolutePath());
    }

    private static List<Record> read(Path file, String batteryId) throws IOException {
        List<Record> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!"cycle,capacity,ambient_temperature".equals(header)) {
                throw new IOException("Unexpected capacity header in " + file);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                if (values.length != 3) {
                    throw new IOException("Malformed row in " + file + ": " + line);
                }
                result.add(new Record(batteryId,
                        Integer.parseInt(values[0].trim()),
                        Double.parseDouble(values[1].trim()),
                        Double.parseDouble(values[2].trim())));
            }
        }
        return result;
    }

    private static Map<String, Integer> findEolCycles(List<Record> records) {
        Map<String, Integer> eol = new HashMap<>();
        for (Record record : records) {
            if (record.capacityAh <= EOL_CAPACITY_AH) {
                eol.merge(record.batteryId, record.cycle, Math::min);
            }
        }
        return eol;
    }

    private static void writeCanonical(List<Record> records, Map<String, Integer> eol,
            Path output) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("battery_id,cycle,capacity_raw_ah,ambient_temperature_c,soh_raw,"
                    + "eol_cycle,rul_cycles,eol_observed");
            writer.newLine();
            for (Record record : records) {
                Integer eolCycle = eol.get(record.batteryId);
                String eolValue = eolCycle == null ? "" : Integer.toString(eolCycle);
                String rulValue = eolCycle == null ? "" : Integer.toString(eolCycle - record.cycle);
                writer.write(String.join(",",
                        record.batteryId,
                        Integer.toString(record.cycle),
                        Double.toString(record.capacityAh),
                        Double.toString(record.ambientTemperatureC),
                        Double.toString(record.capacityAh / NOMINAL_CAPACITY_AH),
                        eolValue,
                        rulValue,
                        Boolean.toString(eolCycle != null)));
                writer.newLine();
            }
        }
    }

    private static void writeSummary(List<Record> records, Map<String, Integer> eol,
            Path output) throws IOException {
        Map<String, List<Record>> grouped = group(records);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("battery_id,capacity_rows,first_cycle,last_cycle,min_capacity_ah,"
                    + "max_capacity_ah,first_soh,last_soh,eol_cycle,eol_observed,"
                    + "capacity_increases,missing_cycle_gaps");
            writer.newLine();
            for (Map.Entry<String, List<Record>> entry : grouped.entrySet()) {
                List<Record> values = entry.getValue();
                values.sort(Comparator.comparingInt(Record::cycle));
                int increases = 0;
                int gaps = 0;
                for (int i = 1; i < values.size(); i++) {
                    if (values.get(i).capacityAh > values.get(i - 1).capacityAh) {
                        increases++;
                    }
                    if (values.get(i).cycle > values.get(i - 1).cycle + 1) {
                        gaps++;
                    }
                }
                Record first = values.get(0);
                Record last = values.get(values.size() - 1);
                Integer eolCycle = eol.get(entry.getKey());
                writer.write(String.join(",",
                        entry.getKey(),
                        Integer.toString(values.size()),
                        Integer.toString(first.cycle),
                        Integer.toString(last.cycle),
                        format(minCapacity(values)),
                        format(maxCapacity(values)),
                        format(first.capacityAh / NOMINAL_CAPACITY_AH),
                        format(last.capacityAh / NOMINAL_CAPACITY_AH),
                        eolCycle == null ? "" : Integer.toString(eolCycle),
                        Boolean.toString(eolCycle != null),
                        Integer.toString(increases),
                        Integer.toString(gaps)));
                writer.newLine();
            }
        }
    }

    private static void writeSvg(List<Record> records, Map<String, Integer> eol,
            Path output) throws IOException {
        Map<String, List<Record>> grouped = group(records);
        int minCycle = records.stream().mapToInt(Record::cycle).min().orElse(0);
        int maxCycle = records.stream().mapToInt(Record::cycle).max().orElse(1);
        double minCapacity = records.stream().mapToDouble(r -> r.capacityAh).min().orElse(0.0);
        double maxCapacity = Math.max(NOMINAL_CAPACITY_AH,
                records.stream().mapToDouble(r -> r.capacityAh).max().orElse(1.0));
        double minSoh = minCapacity / NOMINAL_CAPACITY_AH;
        double maxSoh = maxCapacity / NOMINAL_CAPACITY_AH;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + PLOT_WIDTH
                    + "\" height=\"" + PLOT_HEIGHT + "\" viewBox=\"0 0 "
                    + PLOT_WIDTH + " " + PLOT_HEIGHT + "\">\n");
            writer.write("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
            writer.write("<text x=\"550\" y=\"28\" text-anchor=\"middle\" font-size=\"20\">"
                    + "NASA capacity and SOH trajectories</text>\n");
            writer.write("<line x1=\"" + LEFT + "\" y1=\"" + (PLOT_HEIGHT - BOTTOM)
                    + "\" x2=\"" + (PLOT_WIDTH - RIGHT) + "\" y2=\"" + (PLOT_HEIGHT - BOTTOM)
                    + "\" stroke=\"black\"/><line x1=\"" + LEFT + "\" y1=\"" + TOP
                    + "\" x2=\"" + LEFT + "\" y2=\"" + (PLOT_HEIGHT - BOTTOM)
                    + "\" stroke=\"black\"/>\n");
            writer.write("<text x=\"550\" y=\"640\" text-anchor=\"middle\">Cycle</text>\n");
            writer.write("<text transform=\"translate(18 360) rotate(-90)\" text-anchor=\"middle\">"
                    + "SOH (fraction)</text>\n");
            writeTicks(writer, minCycle, maxCycle, minSoh, maxSoh);

            String[] colors = {"#1565c0", "#2e7d32", "#ef6c00", "#8e24aa"};
            int colorIndex = 0;
            int legendY = 75;
            for (Map.Entry<String, List<Record>> entry : grouped.entrySet()) {
                String color = colors[colorIndex++ % colors.length];
                StringBuilder points = new StringBuilder();
                for (Record record : entry.getValue()) {
                    double x = scale(record.cycle, minCycle, maxCycle, LEFT, PLOT_WIDTH - RIGHT);
                    double y = scale(record.capacityAh / NOMINAL_CAPACITY_AH,
                            minSoh, maxSoh, PLOT_HEIGHT - BOTTOM, TOP);
                    points.append(format(x)).append(',').append(format(y)).append(' ');
                }
                writer.write("<polyline points=\"" + points + "\" fill=\"none\" stroke=\""
                        + color + "\" stroke-width=\"2\"/>\n");
                writer.write("<line x1=\"900\" y1=\"" + legendY + "\" x2=\"925\" y2=\""
                        + legendY + "\" stroke=\"" + color + "\" stroke-width=\"3\"/>");
                writer.write("<text x=\"932\" y=\"" + (legendY + 5) + "\" font-size=\"13\">"
                        + entry.getKey() + "</text>\n");
                legendY += 22;
            }
            double eolY = scale(EOL_CAPACITY_AH / NOMINAL_CAPACITY_AH,
                    minSoh, maxSoh, PLOT_HEIGHT - BOTTOM, TOP);
            writer.write("<line x1=\"" + LEFT + "\" y1=\"" + format(eolY) + "\" x2=\""
                    + (PLOT_WIDTH - RIGHT) + "\" y2=\"" + format(eolY)
                    + "\" stroke=\"#c62828\" stroke-dasharray=\"6,4\"/>");
            writer.write("<text x=\"90\" y=\"" + format(eolY - 6)
                    + "\" fill=\"#c62828\" font-size=\"13\">EOL = 0.70 SOH (1.4 Ah)</text>\n");
            writer.write("</svg>\n");
        }
    }

    private static void writeTicks(BufferedWriter writer, double xMin, double xMax,
            double yMin, double yMax) throws IOException {
        int ticks = 6;
        for (int i = 0; i <= ticks; i++) {
            double fraction = (double) i / ticks;
            double x = LEFT + fraction * (PLOT_WIDTH - LEFT - RIGHT);
            double y = PLOT_HEIGHT - BOTTOM - fraction * (PLOT_HEIGHT - TOP - BOTTOM);
            String xLabel = format(xMin + fraction * (xMax - xMin));
            String yLabel = format(yMin + fraction * (yMax - yMin));
            writer.write("<line x1=\"" + format(x) + "\" y1=\"" + TOP
                    + "\" x2=\"" + format(x) + "\" y2=\"" + (PLOT_HEIGHT - BOTTOM)
                    + "\" stroke=\"#e0e0e0\"/>\n");
            writer.write("<line x1=\"" + LEFT + "\" y1=\"" + format(y)
                    + "\" x2=\"" + (PLOT_WIDTH - RIGHT) + "\" y2=\"" + format(y)
                    + "\" stroke=\"#e0e0e0\"/>\n");
            writer.write("<text x=\"" + format(x) + "\" y=\"" + (PLOT_HEIGHT - BOTTOM + 22)
                    + "\" text-anchor=\"middle\" font-size=\"12\">" + xLabel + "</text>\n");
            writer.write("<text x=\"" + (LEFT - 10) + "\" y=\"" + format(y + 4)
                    + "\" text-anchor=\"end\" font-size=\"12\">" + yLabel + "</text>\n");
        }
    }

    private static Map<String, List<Record>> group(List<Record> records) {
        Map<String, List<Record>> grouped = new java.util.TreeMap<>();
        for (Record record : records) {
            grouped.computeIfAbsent(record.batteryId, ignored -> new ArrayList<>()).add(record);
        }
        return grouped;
    }

    private static double minCapacity(List<Record> records) {
        return records.stream().mapToDouble(r -> r.capacityAh).min().orElse(Double.NaN);
    }

    private static double maxCapacity(List<Record> records) {
        return records.stream().mapToDouble(r -> r.capacityAh).max().orElse(Double.NaN);
    }

    private static double scale(double value, double sourceMin, double sourceMax,
            double targetMin, double targetMax) {
        if (sourceMax == sourceMin) {
            return (targetMin + targetMax) / 2.0;
        }
        return targetMin + (value - sourceMin) * (targetMax - targetMin)
                / (sourceMax - sourceMin);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private record Record(String batteryId, int cycle, double capacityAh,
            double ambientTemperatureC) {
    }
}
