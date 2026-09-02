import java.io.*;
import java.nio.file.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.util.regex.*;

public class DebugPdfPages {
    public static void main(String[] args) throws Exception {
        Path pdf = Paths.get("C:/Users/jalin.you/AJ-WORK/water-law-web/assets/plumbing-spec.pdf");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            java.util.Map<String,Integer> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                s.setStartPage(i + 1); s.setEndPage(i + 1);
                String t = s.getText(doc);
                Matcher m = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)").matcher(t);
                String last = null;
                while (m.find()) last = m.group(1) + "-" + m.group(2);
                if (last != null) map.putIfAbsent(last, i + 1);
            }
            map.entrySet().stream().filter(e -> e.getKey().startsWith("3-")).limit(20)
                    .forEach(e -> System.out.println(e.getKey() + " -> " + e.getValue()));
            System.out.println("3-1 -> " + map.get("3-1"));
            System.out.println("total markers: " + map.size());
        }
    }
}
