package mdm.capstone;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a dependency-free HTML application report from evaluator outputs.
 *
 * <p>The report embeds links to the generated SVG plots and renders prediction,
 * interval, metric, and footprint tables. It can be opened directly in a
 * browser without a server.</p>
 */
public final class RulReportGenerator {
    private RulReportGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: RulReportGenerator <model-output-directory> <report.html>");
            System.exit(2);
        }
        Path input = Paths.get(args[0]);
        Path report = Paths.get(args[1]);
        Files.createDirectories(report.toAbsolutePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write("<!doctype html><html><head><meta charset=\"utf-8\">"
                    + "<title>NASA Battery RUL Report</title>"
                    + "<style>body{font-family:Arial;margin:2rem} table{border-collapse:collapse;"
                    + "margin:1rem 0;font-size:12px} th,td{border:1px solid #bbb;padding:5px}"
                    + "img{max-width:100%;border:1px solid #ddd}</style></head><body>");
            writer.write("<h1>NASA Battery RUL Evaluation</h1>");
            writer.write("<p>Chronological expanding-window evaluation with bootstrap "
                    + "RUL uncertainty intervals and predictor footprint measurements.</p>");
            writeTable(writer, input.resolve("rul_metrics.csv"), "Evaluation metrics");
            writeTable(writer, input.resolve("predictor_profiles.csv"), "Predictor footprint");
            writeTable(writer, input.resolve("rul_predictions.csv"), "Predictions and intervals");
            writer.write("<h2>Plots</h2>");
            for (String plot : List.of("rul_actual_vs_predicted.svg",
                    "rul_absolute_error.svg", "rul_prediction_intervals.svg",
                    "baseline_mae_comparison.svg", "baseline_rmse_comparison.svg",
                    "baseline_mape_comparison.svg")) {
                writer.write("<h3>" + escape(plot) + "</h3><img src=\"plots/"
                        + escape(plot) + "\" alt=\"" + escape(plot) + "\">");
            }
            writer.write("</body></html>");
        }
        System.out.println("Wrote report to " + report.toAbsolutePath());
    }

    private static void writeTable(BufferedWriter writer, Path file, String heading)
            throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        writer.write("<h2>" + escape(heading) + "</h2><table>");
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return;
            }
            writeRow(writer, header.split(",", -1), true);
            String line;
            while ((line = reader.readLine()) != null) {
                writeRow(writer, line.split(",", -1), false);
            }
        }
        writer.write("</table>");
    }

    private static void writeRow(BufferedWriter writer, String[] values, boolean header)
            throws IOException {
        writer.write("<tr>");
        for (String value : values) {
            writer.write("<" + (header ? "th" : "td") + ">" + escape(value)
                    + "</" + (header ? "th" : "td") + ">");
        }
        writer.write("</tr>");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
