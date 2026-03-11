package com.crisismonitor.service;

import com.crisismonitor.model.RiskScore;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Export Service - Generates CSV and PDF exports of crisis monitoring data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final RiskScoreService riskScoreService;

    // ======================== CSV EXPORT ========================

    /**
     * Generate CSV export of all risk scores.
     */
    public byte[] exportRiskScoresCsv() {
        List<RiskScore> scores = riskScoreService.getAllRiskScores();
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("Country,ISO3,ISO2,Score,Risk Level,Food Security,Climate,Conflict,Economic,");
        sb.append("Drivers,Trend,Confidence,Horizon,IPC Phase,GDELT Z-Score,Currency 30d %,Calculated At\n");

        // Data rows
        for (RiskScore s : scores) {
            sb.append(csvEscape(s.getCountryName())).append(',');
            sb.append(csvEscape(s.getIso3())).append(',');
            sb.append(csvEscape(s.getIso2())).append(',');
            sb.append(s.getScore()).append(',');
            sb.append(csvEscape(s.getRiskLevel())).append(',');
            sb.append(s.getFoodSecurityScore()).append(',');
            sb.append(s.getClimateScore()).append(',');
            sb.append(s.getConflictScore()).append(',');
            sb.append(s.getEconomicScore()).append(',');
            sb.append(csvEscape(s.getDrivers() != null ? String.join("; ", s.getDrivers()) : "")).append(',');
            sb.append(csvEscape(s.getTrend() != null ? s.getTrend() : "")).append(',');
            sb.append(s.getConfidence()).append(',');
            sb.append(csvEscape(s.getHorizon() != null ? s.getHorizon() : "")).append(',');
            sb.append(s.getIpcPhase() != null ? s.getIpcPhase() : "").append(',');
            sb.append(s.getGdeltZScore() != null ? String.format("%.2f", s.getGdeltZScore()) : "").append(',');
            sb.append(s.getCurrencyChange30d() != null ? String.format("%.2f", s.getCurrencyChange30d()) : "").append(',');
            sb.append(s.getCalculatedAt() != null ? s.getCalculatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "");
            sb.append('\n');
        }

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ======================== PDF EXPORT ========================

    /**
     * Generate PDF report of all risk scores.
     */
    public byte[] exportRiskScoresPdf() {
        List<RiskScore> scores = riskScoreService.getAllRiskScores();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(document, baos);
            document.open();

            // Title
            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD, new Color(30, 30, 30));
            Paragraph title = new Paragraph("Crisis Monitor - Risk Assessment Report", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);

            // Subtitle with date
            Font subtitleFont = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(100, 100, 100));
            Paragraph subtitle = new Paragraph(
                    "Generated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    subtitleFont
            );
            subtitle.setAlignment(Element.ALIGN_CENTER);
            subtitle.setSpacingAfter(20);
            document.add(subtitle);

            // Summary
            long critical = scores.stream().filter(s -> "CRITICAL".equals(s.getRiskLevel())).count();
            long alert = scores.stream().filter(s -> "ALERT".equals(s.getRiskLevel())).count();
            long warning = scores.stream().filter(s -> "WARNING".equals(s.getRiskLevel())).count();

            Font summaryFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
            document.add(new Paragraph(
                    String.format("Countries monitored: %d | Critical: %d | Alert: %d | Warning: %d",
                            scores.size(), critical, alert, warning),
                    summaryFont
            ));
            document.add(new Paragraph(" ")); // spacer

            // Table
            PdfPTable table = new PdfPTable(8);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{3f, 1.5f, 1.2f, 1.5f, 1.5f, 1.5f, 1.5f, 3f});

            // Header row
            Font headerFont = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
            String[] headers = {"Country", "Score", "Level", "Food Sec.", "Climate", "Conflict", "Economic", "Drivers"};
            for (String header : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
                cell.setBackgroundColor(new Color(44, 62, 80));
                cell.setPadding(6);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(cell);
            }

            // Data rows
            Font dataFont = new Font(Font.HELVETICA, 8, Font.NORMAL);
            Font dataFontBold = new Font(Font.HELVETICA, 8, Font.BOLD);
            boolean altRow = false;

            for (RiskScore s : scores) {
                Color rowBg = altRow ? new Color(245, 245, 245) : Color.WHITE;

                // Country name
                PdfPCell nameCell = new PdfPCell(new Phrase(s.getCountryName(), dataFontBold));
                nameCell.setBackgroundColor(rowBg);
                nameCell.setPadding(4);
                table.addCell(nameCell);

                // Score
                PdfPCell scoreCell = new PdfPCell(new Phrase(String.valueOf(s.getScore()), dataFontBold));
                scoreCell.setBackgroundColor(rowBg);
                scoreCell.setPadding(4);
                scoreCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(scoreCell);

                // Risk Level (colored)
                Color levelColor = switch (s.getRiskLevel()) {
                    case "CRITICAL" -> new Color(220, 53, 69);
                    case "ALERT" -> new Color(255, 140, 0);
                    case "WARNING" -> new Color(255, 193, 7);
                    default -> new Color(100, 100, 100);
                };
                Font levelFont = new Font(Font.HELVETICA, 8, Font.BOLD, levelColor);
                PdfPCell levelCell = new PdfPCell(new Phrase(s.getRiskLevel(), levelFont));
                levelCell.setBackgroundColor(rowBg);
                levelCell.setPadding(4);
                levelCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(levelCell);

                // Sub-scores
                for (int subScore : new int[]{s.getFoodSecurityScore(), s.getClimateScore(), s.getConflictScore(), s.getEconomicScore()}) {
                    PdfPCell subCell = new PdfPCell(new Phrase(String.valueOf(subScore), dataFont));
                    subCell.setBackgroundColor(rowBg);
                    subCell.setPadding(4);
                    subCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                    table.addCell(subCell);
                }

                // Drivers
                String drivers = s.getDrivers() != null ? String.join(", ", s.getDrivers()) : "";
                PdfPCell driversCell = new PdfPCell(new Phrase(drivers, dataFont));
                driversCell.setBackgroundColor(rowBg);
                driversCell.setPadding(4);
                table.addCell(driversCell);

                altRow = !altRow;
            }

            document.add(table);

            // Footer
            document.add(new Paragraph(" "));
            Font footerFont = new Font(Font.HELVETICA, 8, Font.ITALIC, new Color(150, 150, 150));
            Paragraph footer = new Paragraph(
                    "Data sources: FEWS NET (Food Security), Open-Meteo (Climate), GDELT (Conflict Media), Frankfurter (Currency). " +
                    "Scores use 2-of-3 confirmation rule to reduce false positives.",
                    footerFont
            );
            document.add(footer);

            document.close();
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate PDF export: {}", e.getMessage(), e);
            throw new RuntimeException("PDF export failed", e);
        }
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
