import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.TextPosition;

public class DebugCrop extends ExportPlumbingPages {
    public static void main(String[] args) throws Exception {
        Path pdf = Paths.get("C:/Users/jalin.you/AJ-WORK/water-law-web/assets/plumbing-spec.pdf");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PageLinesStripper stripper = new PageLinesStripper();
            List<LineBox> all = new ArrayList<>();
            Map<String, PDPage> pageMap = new HashMap<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                int p = i + 1;
                pageMap.put(String.valueOf(p), doc.getPage(i));
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                stripper.getText(doc);
                all.addAll(stripper.lines);
                stripper.lines.clear();
            }
            for (int p : new int[] { 89, 90 }) {
                System.out.println("=== page " + p + " ===");
                float pageH = pageMap.get(String.valueOf(p)).getMediaBox().getHeight();
                List<LineBox> lines = new ArrayList<>();
                for (LineBox lb : all) if (lb.page == p) lines.add(lb);
                lines = sortLinesTopDown(lines);
                int shown = 0;
                for (LineBox lb : lines) {
                    if (shown++ < 8) System.out.printf("TOP Y=%.0f | %s%n", lb.maxY, lb.text);
                }
                for (LineBox lb : lines) {
                    if (lb.text.contains("A-23") || lb.text.contains("A-24") || lb.text.contains("A-25")
                            || lb.text.contains("表 A")) {
                        System.out.printf("Y=%.0f-%.0f | %s%n", lb.minY, lb.maxY, lb.text);
                    }
                }
                LineBox c23 = findCaptionLine(lines, "表 A-23 各種器具之流出量", "A-23", pageH);
                LineBox c24 = findCaptionLine(lines, "表 A-24 同時使用率", "A-24", pageH);
                System.out.println("caption A-23: " + (c23 == null ? "null" : c23.text + " Y=" + c23.maxY));
                System.out.println("caption A-24: " + (c24 == null ? "null" : c24.text + " Y=" + c24.maxY));
                if (c24 != null) {
                    var rect = computeCropRect(pageMap.get(String.valueOf(p)), lines, c24, "table", "A-24", 2f);
                    System.out.println("A-24 crop: " + rect);
                }
                LineBox c23b = findCaptionLine(lines, "表 A-23 各種器具之流出量", "A-23", pageH);
                if (c23b != null) {
                    var r23 = computeCropRect(pageMap.get(String.valueOf(p)), lines, c23b, "table", "A-23", 2f);
                    System.out.println("A-23 caption Y=" + c23b.maxY + " crop: " + r23);
                }
            }
        }
    }
}
