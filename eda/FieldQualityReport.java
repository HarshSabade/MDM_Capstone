import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates numeric fields and checks that capacity cycles have discharge data.
 *
 * Usage:
 *   java FieldQualityReport "path\to\FY08Q4" "field_quality_report.csv"
 */
public final class FieldQualityReport {
    private FieldQualityReport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java FieldQualityReport <csv-directory> <output-report.csv>");
            System.exit(2);
        }

        Path directory = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        List<FieldSummary> fields = new ArrayList<>();
        List<AlignmentSummary> alignments = new ArrayList<>();

        try (DirectoryStream<Path> capacityFiles = Files.newDirectoryStream(directory, "*_capacity.csv")) {
            for (Path capacityFile : capacityFiles) {
                String name = capacityFile.getFileName().toString();
                String batteryId = name.substring(0, name.length() - "_capacity.csv".length());
                Path cyclesFile = directory.resolve(batteryId + "_cycles.csv");
                fields.addAll(readCapacityFields(capacityFile, batteryId));
                if (Files.exists(cyclesFile)) {
                    fields.addAll(readCycleFields(cyclesFile, batteryId));
                    alignments.add(readAlignment(capacityFile, cyclesFile, batteryId));
                }
            }
        }

        fields.sort((a, b) -> {
            int battery = a.batteryId.compareTo(b.batteryId);
            return battery != 0 ? battery : a.column.compareTo(b.column);
        });

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("battery_id,file_type,column,total_rows,blank_rows,non_numeric_rows,"
                    + "infinite_rows,finite_rows,min_value,max_value");
            writer.newLine();
            for (FieldSummary field : fields) {
                writer.write(field.toCsv());
                writer.newLine();
            }
        }

        Path alignmentOutput = output.resolveSibling("capacity_cycle_alignment.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(alignmentOutput, StandardCharsets.UTF_8)) {
            writer.write("battery_id,capacity_rows,capacity_cycles_with_cycle_data,"
                    + "capacity_cycles_with_discharge_data,capacity_cycles_without_discharge_data");
            writer.newLine();
            for (AlignmentSummary alignment : alignments) {
                writer.write(alignment.toCsv());
                writer.newLine();
            }
        }

        System.out.println("Wrote " + output.toAbsolutePath());
        System.out.println("Wrote " + alignmentOutput.toAbsolutePath());
    }

    private static List<FieldSummary> readCapacityFields(Path file, String batteryId)
            throws IOException {
        String[] columns = {"cycle", "capacity", "ambient_temperature"};
        Map<String, FieldSummary> summaries = createSummaries(batteryId, "capacity", columns);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                for (int i = 0; i < columns.length; i++) {
                    update(summaries.get(columns[i]), i < values.length ? values[i] : "");
                }
            }
        }
        return new ArrayList<>(summaries.values());
    }

    private static List<FieldSummary> readCycleFields(Path file, String batteryId)
            throws IOException {
        String[] columns = {
                "cycle", "ambient_temperature", "Voltage_measured", "Current_measured",
                "Temperature_measured", "Current_charge", "Voltage_charge", "Time",
                "Current_load", "Voltage_load"
        };
        Map<String, FieldSummary> summaries = createSummaries(batteryId, "cycles", columns);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                for (int i = 0; i < columns.length; i++) {
                    int sourceIndex = i == 0 ? 0 : i + 1;
                    update(summaries.get(columns[i]),
                            sourceIndex < values.length ? values[sourceIndex] : "");
                }
            }
        }
        return new ArrayList<>(summaries.values());
    }

    private static AlignmentSummary readAlignment(Path capacityFile, Path cyclesFile,
            String batteryId) throws IOException {
        Set<Integer> capacityCycles = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(capacityFile, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    capacityCycles.add(Integer.parseInt(line.split(",", -1)[0].trim()));
                }
            }
        }

        Set<Integer> cycleIds = new HashSet<>();
        Set<Integer> dischargeCycles = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(cyclesFile, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                int cycle = Integer.parseInt(values[0].trim());
                cycleIds.add(cycle);
                if (values.length > 1 && "discharge".equalsIgnoreCase(values[1].trim())) {
                    dischargeCycles.add(cycle);
                }
            }
        }

        int withCycleData = 0;
        int withDischargeData = 0;
        for (int cycle : capacityCycles) {
            if (cycleIds.contains(cycle)) {
                withCycleData++;
            }
            if (dischargeCycles.contains(cycle)) {
                withDischargeData++;
            }
        }
        return new AlignmentSummary(batteryId, capacityCycles.size(), withCycleData,
                withDischargeData, capacityCycles.size() - withDischargeData);
    }

    private static Map<String, FieldSummary> createSummaries(String batteryId,
            String fileType, String[] columns) {
        Map<String, FieldSummary> summaries = new HashMap<>();
        for (String column : columns) {
            summaries.put(column, new FieldSummary(batteryId, fileType, column));
        }
        return summaries;
    }

    private static void update(FieldSummary summary, String raw) {
        summary.totalRows++;
        String value = raw.trim();
        if (value.isEmpty()) {
            summary.blankRows++;
            return;
        }
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) {
                summary.infiniteRows++;
                return;
            }
            summary.finiteRows++;
            summary.minValue = Math.min(summary.minValue, number);
            summary.maxValue = Math.max(summary.maxValue, number);
        } catch (NumberFormatException exception) {
            summary.nonNumericRows++;
        }
    }

    private static final class FieldSummary {
        private final String batteryId;
        private final String fileType;
        private final String column;
        private long totalRows;
        private long blankRows;
        private long nonNumericRows;
        private long infiniteRows;
        private long finiteRows;
        private double minValue = Double.POSITIVE_INFINITY;
        private double maxValue = Double.NEGATIVE_INFINITY;

        private FieldSummary(String batteryId, String fileType, String column) {
            this.batteryId = batteryId;
            this.fileType = fileType;
            this.column = column;
        }

        private String toCsv() {
            return String.join(",", batteryId, fileType, column,
                    Long.toString(totalRows), Long.toString(blankRows),
                    Long.toString(nonNumericRows), Long.toString(infiniteRows),
                    Long.toString(finiteRows), printable(minValue), printable(maxValue));
        }

        private static String printable(double value) {
            return Double.isFinite(value) ? String.format(Locale.ROOT, "%.12g", value) : "";
        }
    }

    private record AlignmentSummary(String batteryId, int capacityRows,
            int capacityCyclesWithCycleData, int capacityCyclesWithDischargeData,
            int capacityCyclesWithoutDischargeData) {
        private String toCsv() {
            return String.join(",", batteryId, Integer.toString(capacityRows),
                    Integer.toString(capacityCyclesWithCycleData),
                    Integer.toString(capacityCyclesWithDischargeData),
                    Integer.toString(capacityCyclesWithoutDischargeData));
        }
    }
}
