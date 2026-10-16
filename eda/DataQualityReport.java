import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.DirectoryStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creates a compact quality report for NASA battery capacity and cycle CSVs.
 *
 * Usage:
 *   java DataQualityReport "path\to\FY08Q4" "quality_report.csv"
 */
public final class DataQualityReport {
    private static final double EOL_CAPACITY_AH = 1.4;

    private DataQualityReport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java DataQualityReport <csv-directory> <output-report.csv>");
            System.exit(2);
        }

        Path directory = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        if (!Files.isDirectory(directory)) {
            throw new IOException("CSV directory does not exist: " + directory);
        }

        List<Summary> summaries = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*_capacity.csv")) {
            for (Path capacityFile : files) {
                String fileName = capacityFile.getFileName().toString();
                String batteryId = fileName.substring(0, fileName.length() - "_capacity.csv".length());
                summaries.add(readBattery(directory, batteryId, capacityFile));
            }
        }
        summaries.sort((left, right) -> left.batteryId.compareTo(right.batteryId));

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("battery_id,capacity_rows,capacity_duplicate_rows,capacity_bad_rows,"
                    + "first_capacity_cycle,last_capacity_cycle,min_capacity_ah,max_capacity_ah,"
                    + "eol_cycle_at_1_4ah,cycle_rows,cycle_duplicate_rows,cycle_bad_rows,"
                    + "charge_rows,discharge_rows,impedance_rows,other_type_rows,"
                    + "cycle_min,cycle_max,non_monotonic_time_rows,missing_required_columns");
            writer.newLine();
            for (Summary summary : summaries) {
                writer.write(summary.toCsv());
                writer.newLine();
            }
        }

        System.out.println("Wrote " + output.toAbsolutePath());
    }

    private static Summary readBattery(Path directory, String batteryId, Path capacityFile)
            throws IOException {
        Summary summary = readCapacity(capacityFile, batteryId);
        Path cyclesFile = directory.resolve(batteryId + "_cycles.csv");
        if (Files.exists(cyclesFile)) {
            readCycles(cyclesFile, summary);
        } else {
            summary.missingRequiredColumns = "missing_cycles_file";
        }
        return summary;
    }

    private static Summary readCapacity(Path file, String batteryId) throws IOException {
        Summary summary = new Summary(batteryId);
        Set<String> seenRows = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!"cycle,capacity,ambient_temperature".equals(header)) {
                summary.missingRequiredColumns = "capacity_header";
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                summary.capacityRows++;
                if (!seenRows.add(line)) {
                    summary.capacityDuplicateRows++;
                }
                String[] fields = line.split(",", -1);
                if (fields.length < 3) {
                    summary.capacityBadRows++;
                    continue;
                }
                try {
                    int cycle = Integer.parseInt(fields[0].trim());
                    double capacity = Double.parseDouble(fields[1].trim());
                    double temperature = Double.parseDouble(fields[2].trim());
                    if (cycle < 0 || !Double.isFinite(capacity) || !Double.isFinite(temperature)) {
                        summary.capacityBadRows++;
                        continue;
                    }
                    summary.firstCapacityCycle = Math.min(summary.firstCapacityCycle, cycle);
                    summary.lastCapacityCycle = Math.max(summary.lastCapacityCycle, cycle);
                    summary.minCapacity = Math.min(summary.minCapacity, capacity);
                    summary.maxCapacity = Math.max(summary.maxCapacity, capacity);
                    if (capacity <= EOL_CAPACITY_AH && summary.eolCycle == Integer.MAX_VALUE) {
                        summary.eolCycle = cycle;
                    }
                } catch (NumberFormatException exception) {
                    summary.capacityBadRows++;
                }
            }
        }
        return summary;
    }

    private static void readCycles(Path file, Summary summary) throws IOException {
        Set<String> seenRows = new HashSet<>();
        Map<String, Double> lastTimeByCycle = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!("cycle,type,ambient_temperature,Voltage_measured,Current_measured,"
                    + "Temperature_measured,Current_charge,Voltage_charge,Time,Current_load,Voltage_load")
                    .equals(header)) {
                summary.missingRequiredColumns = appendIssue(summary.missingRequiredColumns, "cycle_header");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                summary.cycleRows++;
                if (!seenRows.add(line)) {
                    summary.cycleDuplicateRows++;
                }
                String[] fields = line.split(",", -1);
                if (fields.length < 11) {
                    summary.cycleBadRows++;
                    continue;
                }
                try {
                    int cycle = Integer.parseInt(fields[0].trim());
                    String type = fields[1].trim().toLowerCase(Locale.ROOT);
                    Double time = nullableDouble(fields[8]);
                    if (cycle < 0) {
                        summary.cycleBadRows++;
                        continue;
                    }
                    summary.cycleMin = Math.min(summary.cycleMin, cycle);
                    summary.cycleMax = Math.max(summary.cycleMax, cycle);
                    switch (type) {
                        case "charge" -> summary.chargeRows++;
                        case "discharge" -> summary.dischargeRows++;
                        case "impedance" -> summary.impedanceRows++;
                        default -> summary.otherTypeRows++;
                    }
                    if (time != null) {
                        String key = cycle + ":" + type;
                        Double previousTime = lastTimeByCycle.put(key, time);
                        if (previousTime != null && time < previousTime) {
                            summary.nonMonotonicTimeRows++;
                        }
                    }
                } catch (NumberFormatException exception) {
                    summary.cycleBadRows++;
                }
            }
        }
    }

    private static Double nullableDouble(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : Double.parseDouble(trimmed);
    }

    private static String appendIssue(String current, String issue) {
        return current.isEmpty() ? issue : current + "|" + issue;
    }

    private static final class Summary {
        private final String batteryId;
        private int capacityRows;
        private int capacityDuplicateRows;
        private int capacityBadRows;
        private int firstCapacityCycle = Integer.MAX_VALUE;
        private int lastCapacityCycle = Integer.MIN_VALUE;
        private double minCapacity = Double.POSITIVE_INFINITY;
        private double maxCapacity = Double.NEGATIVE_INFINITY;
        private int eolCycle = Integer.MAX_VALUE;
        private long cycleRows;
        private long cycleDuplicateRows;
        private long cycleBadRows;
        private long chargeRows;
        private long dischargeRows;
        private long impedanceRows;
        private long otherTypeRows;
        private int cycleMin = Integer.MAX_VALUE;
        private int cycleMax = Integer.MIN_VALUE;
        private long nonMonotonicTimeRows;
        private String missingRequiredColumns = "";

        private Summary(String batteryId) {
            this.batteryId = batteryId;
        }

        private String toCsv() {
            return String.join(",",
                    batteryId,
                    Integer.toString(capacityRows),
                    Integer.toString(capacityDuplicateRows),
                    Integer.toString(capacityBadRows),
                    printableInt(firstCapacityCycle),
                    printableInt(lastCapacityCycle),
                    printableDouble(minCapacity),
                    printableDouble(maxCapacity),
                    printableInt(eolCycle),
                    Long.toString(cycleRows),
                    Long.toString(cycleDuplicateRows),
                    Long.toString(cycleBadRows),
                    Long.toString(chargeRows),
                    Long.toString(dischargeRows),
                    Long.toString(impedanceRows),
                    Long.toString(otherTypeRows),
                    printableInt(cycleMin),
                    printableInt(cycleMax),
                    Long.toString(nonMonotonicTimeRows),
                    missingRequiredColumns);
        }

        private static String printableInt(int value) {
            return value == Integer.MAX_VALUE || value == Integer.MIN_VALUE ? "" : Integer.toString(value);
        }

        private static String printableDouble(double value) {
            return Double.isFinite(value) ? Double.toString(value) : "";
        }
    }
}
