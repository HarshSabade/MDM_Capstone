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
import java.util.Map;

/**
 * Streams charge/discharge CSVs and summarizes numeric signals by battery and type.
 *
 * Usage:
 *   java CycleSignalSummary "path\to\FY08Q4" "path\to\cycle-signal-summary.csv"
 */
public final class CycleSignalSummary {
    private static final String[] NUMERIC_COLUMNS = {
            "ambient_temperature", "Voltage_measured", "Current_measured",
            "Temperature_measured", "Current_charge", "Voltage_charge", "Time",
            "Current_load", "Voltage_load"
    };

    private CycleSignalSummary() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java CycleSignalSummary <csv-directory> <output-report.csv>");
            System.exit(2);
        }
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        Map<String, Stats> stats = new HashMap<>();

        try (DirectoryStream<Path> files = Files.newDirectoryStream(input, "*_cycles.csv")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String batteryId = name.substring(0, name.length() - "_cycles.csv".length());
                read(file, batteryId, stats);
            }
        }

        List<Stats> sorted = new ArrayList<>(stats.values());
        sorted.sort(Comparator.comparing((Stats s) -> s.batteryId)
                .thenComparing(s -> s.type).thenComparing(s -> s.column));
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("battery_id,type,column,total_rows,finite_rows,blank_rows,"
                    + "non_numeric_rows,min,max,mean");
            writer.newLine();
            for (Stats value : sorted) {
                writer.write(value.toCsv());
                writer.newLine();
            }
        }
        System.out.println("Wrote " + output.toAbsolutePath());
    }

    private static void read(Path file, String batteryId, Map<String, Stats> stats)
            throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IOException("Empty file: " + file);
            }
            String[] headers = header.split(",", -1);
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                index.put(headers[i].trim(), i);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                String type = value(values, index.get("type")).toLowerCase();
                if (!"charge".equals(type) && !"discharge".equals(type)) {
                    continue;
                }
                for (String column : NUMERIC_COLUMNS) {
                    Stats current = stats.computeIfAbsent(
                            batteryId + "\u0000" + type + "\u0000" + column,
                            ignored -> new Stats(batteryId, type, column));
                    current.update(value(values, index.get(column)));
                }
            }
        }
    }

    private static String value(String[] values, Integer index) {
        return index == null || index >= values.length ? "" : values[index].trim();
    }

    private static final class Stats {
        private final String batteryId;
        private final String type;
        private final String column;
        private long totalRows;
        private long finiteRows;
        private long blankRows;
        private long nonNumericRows;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;
        private double mean;

        private Stats(String batteryId, String type, String column) {
            this.batteryId = batteryId;
            this.type = type;
            this.column = column;
        }

        private void update(String raw) {
            totalRows++;
            if (raw.isEmpty()) {
                blankRows++;
                return;
            }
            try {
                double value = Double.parseDouble(raw);
                if (!Double.isFinite(value)) {
                    nonNumericRows++;
                    return;
                }
                finiteRows++;
                min = Math.min(min, value);
                max = Math.max(max, value);
                mean += (value - mean) / finiteRows;
            } catch (NumberFormatException exception) {
                nonNumericRows++;
            }
        }

        private String toCsv() {
            return String.join(",", batteryId, type, column,
                    Long.toString(totalRows), Long.toString(finiteRows),
                    Long.toString(blankRows), Long.toString(nonNumericRows),
                    finiteRows == 0 ? "" : Double.toString(min),
                    finiteRows == 0 ? "" : Double.toString(max),
                    finiteRows == 0 ? "" : Double.toString(mean));
        }
    }
}
