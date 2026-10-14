
package com.datastory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Controller
public class WebController {

    private static final String SESSION_ANALYSIS = "analysis";

    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;

    public WebController(
            AnalysisService analysisService,
            ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------
    // HOME
    // ---------------------------------------------------------

    @GetMapping("/")
    public String home() {
        return "index";
    }

    // ---------------------------------------------------------
    // CSV ANALYSIS
    // ---------------------------------------------------------

    @PostMapping("/analyze")
    public String analyze(
            @RequestParam(value = "file", required = false)
            MultipartFile file,
            Model model,
            HttpSession session) {

        try {
            if (file == null || file.isEmpty()) {
                model.addAttribute(
                        "error",
                        "Please select a CSV file before clicking Analyze CSV."
                );
                return "index";
            }

            String filename = file.getOriginalFilename();

            if (filename == null ||
                    !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                model.addAttribute(
                        "error",
                        "Please upload a valid .csv file."
                );
                return "index";
            }

            Map<String, Object> result = analysisService.analyze(file);

            session.setAttribute(SESSION_ANALYSIS, result);

            addDashboardModel(model, result);

            return "index";

        } catch (Exception ex) {
            ex.printStackTrace();

            model.addAttribute(
                    "error",
                    "CSV analysis failed: " + safeMessage(ex)
            );

            return "index";
        }
    }

    /**
     * Supplies the analysis result and JSON payload expected by index.html.
     *
     * The template currently uses ${result...} for the summary and tables,
     * so both "result" and "a" are populated for compatibility.
     */
    private void addDashboardModel(
            Model model,
            Map<String, Object> result) throws IOException {

        model.addAttribute("result", result);
        model.addAttribute("a", result);

        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("headers", result.get("headers"));
        payload.put("rows", result.get("allRows"));
        payload.put("totalRows", result.get("totalRows"));
        payload.put("columns", result.get("columns"));
        payload.put("missingTotal", result.get("missingTotal"));
        payload.put("duplicates", result.get("duplicates"));
        payload.put("completeness", result.get("completeness"));

        model.addAttribute(
                "dashboardJson",
                objectMapper.writeValueAsString(payload)
        );
    }

    // ---------------------------------------------------------
    // TEXT REPORT EXPORT
    // ---------------------------------------------------------

    @GetMapping("/export/report")
    public void report(
            HttpSession session,
            HttpServletResponse response) throws IOException {

        Map<?, ?> analysis = currentAnalysis(session, response);

        if (analysis == null) {
            return;
        }

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/plain; charset=UTF-8");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"datastory-report.txt\""
        );

        try (PrintWriter out = response.getWriter()) {
            out.println("DataStory Analysis Report");
            out.println("=========================");
            out.println();

            out.println("File: " + value(analysis.get("filename")));
            out.println("Rows: " + value(analysis.get("totalRows")));
            out.println("Columns: " + value(analysis.get("columns")));
            out.println("Completeness: " +
                    value(analysis.get("completeness")) + "%");
            out.println("Missing values: " +
                    value(analysis.get("missingTotal")));
            out.println("Duplicate rows: " +
                    value(analysis.get("duplicates")));

            out.println();
            out.println("Column statistics");
            out.println("-----------------");

            Object statsObject = analysis.get("stats");

            if (statsObject instanceof Iterable<?> stats) {
                for (Object stat : stats) {
                    out.println(String.valueOf(stat));
                }
            } else {
                out.println("No statistics available.");
            }

            out.println();
            out.println("Data quality notes");
            out.println("------------------");

            Object qualityObject = analysis.get("quality");

            if (qualityObject instanceof Iterable<?> quality) {
                for (Object item : quality) {
                    out.println("- " + item);
                }
            } else {
                out.println("No quality notes available.");
            }
        }
    }

    // ---------------------------------------------------------
    // CLEANED CSV EXPORT
    // ---------------------------------------------------------

    @GetMapping("/export/cleaned.csv")
    public void exportCleanedCsv(
            @RequestParam(defaultValue = "false")
            boolean dropDuplicates,
            @RequestParam(defaultValue = "false")
            boolean fillMissing,
            HttpSession session,
            HttpServletResponse response) throws IOException {

        Map<?, ?> analysis = currentAnalysis(session, response);

        if (analysis == null) {
            return;
        }

        Object headersObject = analysis.get("headers");
        Object rowsObject = analysis.get("allRows");

        if (!(headersObject instanceof List<?>) ||
                !(rowsObject instanceof List<?>)) {
            response.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "The dataset is missing exportable rows or headers."
            );
            return;
        }

        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) headersObject;

        @SuppressWarnings("unchecked")
        List<List<String>> sourceRows = (List<List<String>>) rowsObject;

        List<List<String>> cleanedRows = new ArrayList<>();
        Set<String> seenRows = new HashSet<>();

        for (List<String> original : sourceRows) {
            List<String> row = new ArrayList<>(original);

            if (fillMissing) {
                for (int i = 0; i < row.size(); i++) {
                    String cell = row.get(i);

                    if (cell == null || cell.isBlank()) {
                        row.set(i, "Unknown");
                    }
                }
            }

            // Encode the row as JSON for a safer duplicate key.
            String key = duplicateKey(row);

            if (!dropDuplicates || seenRows.add(key)) {
                cleanedRows.add(row);
            }
        }

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"datastory-cleaned.csv\""
        );

        try (PrintWriter out = response.getWriter()) {
            // UTF-8 BOM improves compatibility with spreadsheet programs.
            out.write('\uFEFF');

            writeCsvRow(out, headers);

            for (List<String> row : cleanedRows) {
                writeCsvRow(out, row);
            }
        }
    }

    private String duplicateKey(List<String> row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (Exception ex) {
            return String.join("\u001F", row);
        }
    }

    private void writeCsvRow(
            PrintWriter out,
            List<String> cells) {

        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.print(',');
            }

            String cell = cells.get(i) == null ? "" : cells.get(i);

            out.print('"');
            out.print(cell.replace("\"", "\"\""));
            out.print('"');
        }

        out.println();
    }

    // ---------------------------------------------------------
    // ASK YOUR DATA
    // ---------------------------------------------------------

    /**
     * Supports both:
     * 1. JSON requests: {"question":"How many rows are there?"}
     * 2. Form requests: question=How many rows are there?
     */
    @PostMapping(
            value = "/api/ask",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @ResponseBody
    public Map<String, Object> ask(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(value = "question", required = false)
            String formQuestion,
            HttpSession session) {

        String question = formQuestion;

        if (body != null && body.get("question") != null) {
            question = String.valueOf(body.get("question"));
        }

        Object analysisObject = session.getAttribute(SESSION_ANALYSIS);

        if (!(analysisObject instanceof Map<?, ?> data)) {
            return answer(
                    "Upload and analyze a dataset before asking questions."
            );
        }

        Object headersObject = data.get("headers");
        Object rowsObject = data.get("allRows");

        if (!(headersObject instanceof List<?>) ||
                !(rowsObject instanceof List<?>)) {
            return answer(
                    "The current dataset does not contain the required analysis data."
            );
        }

        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) headersObject;

        @SuppressWarnings("unchecked")
        List<List<String>> rows = (List<List<String>>) rowsObject;

        String q = question == null
                ? ""
                : question.toLowerCase(Locale.ROOT).trim();

        if (q.isBlank()) {
            return answer("Please type a question about your dataset.");
        }

        if (q.contains("how many") ||
                q.contains("row count") ||
                q.contains("number of rows") ||
                q.contains("count rows") ||
                q.equals("rows")) {

            return answer(
                    "This dataset contains " + rows.size() +
                            " rows and " + headers.size() + " columns."
            );
        }

        if (q.contains("how many columns") ||
                q.contains("number of columns") ||
                q.contains("count columns")) {

            return answer(
                    "This dataset contains " + headers.size() + " columns."
            );
        }

        // Prefer exact header matches, longest first, to reduce ambiguity.
        List<Integer> matchingColumns = new ArrayList<>();

        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);

            if (header != null &&
                    !header.isBlank() &&
                    q.contains(header.toLowerCase(Locale.ROOT))) {
                matchingColumns.add(i);
            }
        }

        matchingColumns.sort((left, right) ->
                Integer.compare(
                        headers.get(right).length(),
                        headers.get(left).length()
                )
        );

        if (matchingColumns.isEmpty()) {
            return answer(
                    "I couldn't identify a column in your question. " +
                    "Use the exact column name and ask about missing values, " +
                    "distinct values, average, sum, maximum or minimum."
            );
        }

        int columnIndex = matchingColumns.get(0);
        String header = headers.get(columnIndex);

        if (q.contains("missing") ||
                q.contains("empty") ||
                q.contains("blank")) {

            long missing = rows.stream().filter(row ->
                    columnIndex >= row.size() ||
                    row.get(columnIndex) == null ||
                    row.get(columnIndex).isBlank()
            ).count();

            return answer(
                    "Column '" + header + "' has " + missing +
                    " missing value(s) out of " + rows.size() + " rows."
            );
        }

        if (q.contains("unique") ||
                q.contains("distinct")) {

            long unique = rows.stream()
                    .filter(row -> columnIndex < row.size())
                    .map(row -> row.get(columnIndex))
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .count();

            return answer(
                    "Column '" + header + "' contains " + unique +
                    " distinct non-empty value(s)."
            );
        }

        boolean asksSum = q.contains("sum");
        boolean asksMaximum =
                q.contains("maximum") || q.contains("highest") ||
                q.contains("largest") || q.contains("max");
        boolean asksMinimum =
                q.contains("minimum") || q.contains("lowest") ||
                q.contains("smallest") || q.contains("min");
        boolean asksAverage =
                q.contains("average") || q.contains("mean");

        if (asksSum || asksMaximum || asksMinimum || asksAverage) {
            List<Double> numbers = new ArrayList<>();

            for (List<String> row : rows) {
                if (columnIndex < row.size()) {
                    Double number = parseNumber(row.get(columnIndex));

                    if (number != null && Double.isFinite(number)) {
                        numbers.add(number);
                    }
                }
            }

            if (numbers.isEmpty()) {
                return answer(
                        "Column '" + header +
                        "' has no recognizable numeric values for this calculation."
                );
            }

            double value;
            String label;

            if (asksSum) {
                value = numbers.stream()
                        .mapToDouble(Double::doubleValue)
                        .sum();
                label = "sum";
            } else if (asksMaximum) {
                value = numbers.stream()
                        .mapToDouble(Double::doubleValue)
                        .max()
                        .orElse(0);
                label = "maximum";
            } else if (asksMinimum) {
                value = numbers.stream()
                        .mapToDouble(Double::doubleValue)
                        .min()
                        .orElse(0);
                label = "minimum";
            } else {
                value = numbers.stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0);
                label = "average";
            }

            return answer(
                    String.format(
                            Locale.ROOT,
                            "The %s of '%s' is %.2f, calculated from %d numeric value(s).",
                            label,
                            header,
                            value,
                            numbers.size()
                    )
            );
        }

        return answer(
                "I recognized column '" + header +
                "', but not the calculation. Try missing values, distinct values, " +
                "average, sum, maximum or minimum."
        );
    }

    private Map<String, Object> answer(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", message);
        return result;
    }

    // ---------------------------------------------------------
    // SESSION AND HELPERS
    // ---------------------------------------------------------

    private Map<?, ?> currentAnalysis(
            HttpSession session,
            HttpServletResponse response) throws IOException {

        Object object = session.getAttribute(SESSION_ANALYSIS);

        if (!(object instanceof Map<?, ?> analysis)) {
            response.sendError(
                    HttpServletResponse.SC_NOT_FOUND,
                    "Please analyze a dataset before exporting."
            );
            return null;
        }

        return analysis;
    }

    private Double parseNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            String normalized = value
                    .replace(",", "")
                    .replace("$", "")
                    .replace("₹", "")
                    .trim();

            double parsed = Double.parseDouble(normalized);

            return Double.isFinite(parsed) ? parsed : null;

        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String value(Object object) {
        return object == null ? "N/A" : String.valueOf(object);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();

        if (message == null || message.isBlank()) {
            return "Unknown error. Check the application terminal.";
        }

        // Avoid rendering a long stack trace or raw internal details in the page.
        return message.length() > 400
                ? message.substring(0, 400) + "..."
                : message;
    }
}
