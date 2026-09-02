import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

public class ExportPlumbingPages {
    static class LineBox {
        int page;
        float minX, minY, maxX, maxY;
        String text;

        LineBox(int page, float minX, float minY, float maxX, float maxY, String text) {
            this.page = page;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.text = clean(text);
        }
    }

    static class MediaRef {
        String type, ref, caption, crop;
        int page;
    }

    static class Article {
        String id, title, label, body;
        List<Integer> pages = new ArrayList<>();
        List<MediaRef> media = new ArrayList<>();
    }

    static class Chapter {
        String id, icon, title, subtitle, desc;
        List<Article> articles = new ArrayList<>();
    }

    static class PageLinesStripper extends PDFTextStripper {
        final List<LineBox> lines = new ArrayList<>();
        int activePage = 0;

        PageLinesStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            activePage = getCurrentPageNo();
        }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            if (textPositions.isEmpty()) return;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = 0, maxY = 0;
            for (TextPosition tp : textPositions) {
                minX = Math.min(minX, tp.getX());
                minY = Math.min(minY, tp.getY());
                maxX = Math.max(maxX, tp.getX() + tp.getWidth());
                maxY = Math.max(maxY, tp.getY() + tp.getHeight());
            }
            lines.add(new LineBox(activePage, minX, minY, maxX, maxY, string));
        }
    }

    static String normalizeText(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC);
    }

    static String clean(String s) {
        if (s == null) return "";
        return normalizeText(s).replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    static String shortLabel(String text) {
        text = clean(text);
        if (text.length() <= 36) return text;
        return text.substring(0, 36) + "\u2026";
    }

    static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    static String chapterIcon(String title) {
        if (title.contains("\u7e3d\u5247")) return "\uD83D\uDCCB";
        if (title.contains("\u914d\u7ba1")) return "\uD83D\uDCD0";
        if (title.contains("\u7d66\u6c34")) return "\uD83D\uDCA7";
        if (title.contains("\u6392\u6c34")) return "\uD83C\uDF27\uFE0F";
        return "\uD83D\uDCCA";
    }

    static boolean isPageMarker(String line) {
        String t = line.replace('\uFF0D', '-').trim();
        return t.matches("\\d+\\s*-\\s*\\d+") || t.matches("\u9644\u9304-\\s*\\d+");
    }

    static boolean hasPageFooter(List<LineBox> pageLines, float pageH) {
        for (LineBox lb : pageLines) {
            if (lb.minY > pageH * 0.18f) continue;
            String t = lb.text.replace('\uFF0D', '-').trim();
            if (t.matches("\\d+\\s*-\\s*\\d+") || t.matches("\u9644\u9304-\\s*\\d+")) return true;
        }
        return false;
    }

    static List<int[]> buildMarkerEvents(List<String> lines, int scanStart, List<Integer> pdfPageTimeline, int defaultPdfPage) {
        List<int[]> markerEvents = new ArrayList<>();
        List<Integer> textMarkerLines = new ArrayList<>();
        for (int i = scanStart; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.isEmpty() && isPageMarker(line)) textMarkerLines.add(i);
        }

        List<Integer> timeline = new ArrayList<>(pdfPageTimeline);
        if (timeline.isEmpty() && !textMarkerLines.isEmpty()) {
            for (int k = 0; k < textMarkerLines.size(); k++) timeline.add(defaultPdfPage + k);
        }

        int n = Math.min(textMarkerLines.size(), timeline.size());
        for (int k = 0; k < n; k++) markerEvents.add(new int[] { textMarkerLines.get(k), timeline.get(k) });
        return markerEvents;
    }

    static int pageAtLine(int lineIdx, List<int[]> markerEvents, int defaultPage) {
        int page = defaultPage;
        for (int[] ev : markerEvents) {
            if (ev[0] <= lineIdx) page = ev[1];
            else break;
        }
        return page;
    }

    static boolean isFigureLine(String line) {
        String t = clean(line);
        return t.matches("\u5716\\s*[A-Za-z]?-?\\d+[-\\.\uFF0D][\\d\\-\uFF0D]*.*")
                || t.matches("\u8868\\s*[A-Za-z]?-?\\d+[-\\.\uFF0D][\\d\\-\uFF0D]*.*")
                || t.matches("\u8868\\s+A-\\d+.*")
                || t.matches("\u5716\\s+A-\\d+.*")
                || t.matches("\u8868\\s*\\d+[-\\.\uFF0D][\\d\\-\uFF0D]+.*")
                || t.matches("\u5716\\s*\\d+[-\\.\uFF0D][\\d\\-\uFF0D]+.*");
    }

    static String mediaType(String line) {
        if (clean(line).startsWith("\u5716")) return "figure";
        return "table";
    }

    static String mediaRef(String line) {
        String t = clean(line);
        Matcher m = Pattern.compile("[\u5716\u8868]\\s*([A-Za-z]?-?[\\d\\-\uFF0D]+)").matcher(t);
        if (m.find()) return m.group(1).replace('\uFF0D', '-').replaceAll("\\s+", "");
        m = Pattern.compile("[\u5716\u8868]\\s*(\\d+[-\\.\uFF0D][\\d\\-\uFF0D]+)").matcher(t);
        if (m.find()) return m.group(1).replace('\uFF0D', '-').replaceAll("\\s+", "");
        return t.substring(0, Math.min(16, t.length())).replaceAll("\\s+", "");
    }

    static String mediaArtId(String line) {
        String ref = mediaRef(line);
        String prefix = clean(line).startsWith("\u5716") ? "\u5716-" : "\u8868-";
        return prefix + ref;
    }

    static String cropFileName(String line) {
        String type = mediaType(line).equals("figure") ? "fig" : "tab";
        return type + "-" + mediaRef(line).replace('.', '-').replace('/', '-') + ".png";
    }

    static boolean isNumericGarbage(String text) {
        text = clean(text);
        if (text.isEmpty()) return true;
        if (text.matches("^[\\d\\.\\s\\-,\\*\\uFF0C\\u3001/]+$")) return true;
        if (text.matches("^[\\d\\.]+\\s+[\\d\\.\\s\\-]+$")) return true;
        return text.length() < 8 && text.matches(".*\\d.*") && !text.matches(".*[\u4e00-\u9fff].*");
    }

    static String[] parseAppendixMainSection(String line) {
        if (isPageMarker(line)) return null;
        Matcher m = Pattern.compile("^(\\d+)\\s+(.+)$").matcher(line);
        if (!m.matches() || line.matches("\\d+\\.\\d+.*")) return null;
        int num = Integer.parseInt(m.group(1));
        String title = clean(m.group(2));
        if (num < 1 || num > 5 || title.length() <= 4) return null;
        if (!title.matches(".*[\u4e00-\u9fff].*")) return null;
        if (!title.matches(".*(\u8a08\u7b97|\u5206\u5340|\u9078\u7528|\u65b9\u6cd5|\u7cfb\u7d71).*")) return null;
        if (title.matches("^[\\d\\.\\s\\-]+$")) return null;
        return new String[] { "A-" + num, title };
    }

    static boolean isAppendixSubSection(String line) {
        return line.matches("\\d+\\.\\d+(?:\\.\\d+)?\\s+.*");
    }

    static void flushArticle(Chapter chapter, Article art, List<String> bodyLines, int startPage, int endPage) {
        if (chapter == null || art == null) return;
        boolean tableOrFigure = art.id.startsWith("\u8868-") || art.id.startsWith("\u5716-");
        List<String> paras = new ArrayList<>();
        if (tableOrFigure) {
            paras.add(art.title);
        } else {
            for (String line : bodyLines) {
                String t = clean(line);
                if (!t.isEmpty() && !isNumericGarbage(t)) paras.add(t);
            }
        }
        String body = String.join("\n\n", paras);
        if (body.isEmpty()) body = art.title;
        art.body = body;
        if (tableOrFigure) {
            art.label = art.title.length() <= 48 ? art.title : shortLabel(art.title);
        } else if (art.id.matches("A-[1-5]")) {
            art.label = art.title;
        } else {
            String flatBody = clean(body.replace("\n\n", " "));
            String labelSource;
            if (art.title.isEmpty()) {
                labelSource = flatBody;
            } else if (flatBody.startsWith(art.title) || flatBody.equals(art.title)) {
                labelSource = art.title;
            } else {
                labelSource = art.title + " " + flatBody;
            }
            art.label = shortLabel(labelSource);
        }
        LinkedHashSet<Integer> pageSet = new LinkedHashSet<>();
        int lo = Math.min(startPage, Math.max(endPage, startPage));
        int hi = Math.max(startPage, endPage);
        for (int p = lo; p <= hi; p++) if (p > 0) pageSet.add(p);
        for (MediaRef m : art.media) if (m.page > 0) pageSet.add(m.page);
        art.pages.addAll(pageSet);
        chapter.articles.add(art);
    }

    static String normalizeMatch(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u00A0]", "")
                .replace('\uFF0D', '-')
                .replace('量', '量')
                .replace('類', '類')
                .replace('說', '說')
                .toLowerCase();
    }

    static List<LineBox> sortLinesTopDown(List<LineBox> pageLines) {
        List<LineBox> sorted = new ArrayList<>(pageLines);
        sorted.sort((a, b) -> {
            int byY = Float.compare(b.maxY, a.maxY);
            return byY != 0 ? byY : Float.compare(a.minX, b.minX);
        });
        return sorted;
    }

    static boolean isCaptionForRef(String text, String ref) {
        String t = normalizeMatch(text);
        String r = normalizeMatch(ref);
        if (Pattern.compile("(?:\u8868|\u5716)\\s*" + Pattern.quote(r) + "(?:\\s|[^0-9]|$)").matcher(t).find()) {
            return true;
        }
        if (Pattern.compile("^" + Pattern.quote(r) + "(?:\\s|[^0-9]|$)").matcher(t).find()) {
            return text.matches(".*[\u4e00-\u9fff].*");
        }
        return false;
    }

    static boolean containsWholeRef(String text, String ref) {
        String r = Pattern.quote(normalizeMatch(ref));
        return Pattern.compile("(?:\u8868|\u5716)?\\s*" + r + "(?:\\s|[^0-9]|$)", Pattern.CASE_INSENSITIVE)
                .matcher(normalizeMatch(text)).find();
    }

    static boolean isExactMediaRefLine(String text, String ref) {
        String trimmed = clean(text);
        String want = normalizeMatch(ref);
        if (trimmed.startsWith("\u8868") || trimmed.startsWith("\u5716") || isFigureLine(trimmed)) {
            if (normalizeMatch(mediaRef(trimmed)).equals(want)) return true;
        }
        Matcher m = Pattern.compile("^(?:\u8868\\s*)?(A-\\d+)\\b").matcher(trimmed);
        if (m.find() && normalizeMatch(m.group(1)).equals(want)) return true;
        return isCaptionForRef(trimmed, ref);
    }

    static LineBox findCaptionLine(List<LineBox> pageLines, String caption, String ref, float pageH) {
        String cNorm = normalizeMatch(caption);
        String refNorm = normalizeMatch(ref);
        LineBox best = null;
        int bestScore = 0;
        for (LineBox lb : pageLines) {
            String tNorm = normalizeMatch(lb.text);
            int score = 0;
            if (isExactMediaRefLine(lb.text, ref)) {
                if (tNorm.equals(cNorm)) score = 100;
                else if (tNorm.contains(cNorm) || cNorm.contains(tNorm)) score = 92;
                else score = 88;
            } else if (isCaptionForRef(lb.text, ref)) {
                score = 80;
            } else if (containsWholeRef(lb.text, ref)) {
                score = 30;
            } else if (tNorm.contains(refNorm)) {
                score = 15;
            }

            if (score >= 75) {
                score += Math.round((lb.maxY / Math.max(pageH, 1f)) * 12);
                if (lb.text.trim().startsWith("\u8868") || lb.text.trim().startsWith("\u5716")) score += 8;
            }
            if (score > bestScore) {
                bestScore = score;
                best = lb;
            }
        }
        return bestScore >= 75 ? best : null;
    }

    static LineBox findCaptionLineAnyPage(List<LineBox> allLines, int page, String caption, String ref,
                                          Map<String, PDPage> pageMap) {
        List<LineBox> primary = new ArrayList<>();
        for (LineBox lb : allLines) if (lb.page == page) primary.add(lb);

        PDPage pdPage = pageMap.get(String.valueOf(page));
        float pageH = pdPage != null ? pdPage.getMediaBox().getHeight() : 842f;
        LineBox hit = findCaptionLine(primary, caption, ref, pageH);
        if (hit != null) return hit;

        if (page > 1) {
            List<LineBox> prev = new ArrayList<>();
            for (LineBox lb : allLines) if (lb.page == page - 1) prev.add(lb);
            pdPage = pageMap.get(String.valueOf(page - 1));
            pageH = pdPage != null ? pdPage.getMediaBox().getHeight() : pageH;
            hit = findCaptionLine(prev, caption, ref, pageH);
            if (hit != null) return hit;
        }

        List<LineBox> nearby = new ArrayList<>();
        for (LineBox lb : allLines) if (Math.abs(lb.page - page) <= 1) nearby.add(lb);
        return findCaptionLine(nearby, caption, ref, pageH);
    }

    static boolean referencesOtherTable(String text, String ref) {
        String cur = normalizeMatch(ref);
        Matcher m = Pattern.compile("\u8868\\s*(A-[\\d]+)").matcher(text);
        while (m.find()) {
            if (!normalizeMatch(m.group(1)).equals(cur)) return true;
        }
        m = Pattern.compile("\u8868\\s*(\\d+-\\d+)").matcher(text);
        while (m.find()) {
            if (!normalizeMatch(m.group(1)).equals(cur)) return true;
        }
        return false;
    }

    static boolean isNextMediaBoundary(String text, String ref, LineBox captionLine) {
        String trimmed = clean(text);
        if (isPageMarker(trimmed)) return true;
        if (referencesOtherTable(trimmed, ref)) return true;
        Matcher appendix = Pattern.compile("^(?:\u8868\\s*)?(A-\\d+)\\b").matcher(trimmed);
        if (appendix.find() && !normalizeMatch(appendix.group(1)).equals(normalizeMatch(ref))) return true;
        if (trimmed.startsWith("\u8aaa\u660e") || trimmed.startsWith("\uFEF4\u660e")) return true;
        if (trimmed.matches("^\\(\\d+\\).*")) return true;
        if (parseAppendixMainSection(trimmed) != null || isAppendixSubSection(trimmed)) return true;
        if (!isFigureLine(trimmed)) return false;
        String nextRef = normalizeMatch(mediaRef(trimmed));
        String curRef = normalizeMatch(ref);
        if (!nextRef.equals(curRef)) return true;
        return normalizeMatch(trimmed).length() + 8 < normalizeMatch(captionLine.text).length();
    }

    static Rectangle computeContinuationCropRect(PDPage page, List<LineBox> pageLines, String ref, float scale) {
        PDRectangle box = page.getMediaBox();
        float pageH = box.getHeight();
        float pageW = box.getWidth();
        float footerTop = pageH * 0.08f;
        float margin = 8f;
        float regionTopPdf = pageH - 28f;
        float regionBottomPdf = footerTop;

        List<LineBox> sorted = sortLinesTopDown(pageLines);
        LineBox dummy = sorted.isEmpty() ? new LineBox(0, 0, 0, pageH, pageH, "") : sorted.get(0);

        for (LineBox next : sorted) {
            if (next.maxY > regionTopPdf - 16f) continue;
            String text = clean(next.text);
            if (isNextMediaBoundary(text, ref, dummy)) {
                regionBottomPdf = Math.max(footerTop, next.maxY + 8f);
                break;
            }
        }

        if (regionTopPdf - regionBottomPdf < pageH * 0.04f) return null;

        float minX = pageW;
        float maxX = 0f;
        boolean found = false;
        for (LineBox lb : pageLines) {
            if (lb.maxY <= regionTopPdf + 6f && lb.minY >= regionBottomPdf - 6f) {
                minX = Math.min(minX, lb.minX);
                maxX = Math.max(maxX, lb.maxX);
                found = true;
            }
        }
        if (!found) return null;
        minX = Math.max(pageW * 0.03f, minX - margin);
        maxX = Math.min(pageW * 0.97f, maxX + margin);

        int x = Math.max(0, (int) Math.round(minX * scale));
        int y = Math.max(0, (int) Math.round((pageH - regionTopPdf) * scale));
        int w = Math.min((int) (pageW * scale) - x, (int) Math.round((maxX - minX) * scale));
        int h = Math.min((int) (pageH * scale) - y, (int) Math.round((regionTopPdf - regionBottomPdf) * scale));
        if (w < 40 || h < 40) return null;
        return new Rectangle(x, y, w, h);
    }

    static BufferedImage stitchVertical(BufferedImage top, BufferedImage bottom) {
        int w = Math.max(top.getWidth(), bottom.getWidth());
        int h = top.getHeight() + bottom.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(top, (w - top.getWidth()) / 2, 0, null);
        g.drawImage(bottom, (w - bottom.getWidth()) / 2, top.getHeight(), null);
        g.dispose();
        return out;
    }

    static Rectangle computeCropRect(PDPage page, List<LineBox> pageLines, LineBox captionLine, String type,
                                     String ref, float scale) {
        PDRectangle box = page.getMediaBox();
        float pageH = box.getHeight();
        float pageW = box.getWidth();
        float margin = 8f;
        float footerTop = pageH * 0.08f;
        float headerBottom = pageH * 0.90f;

        List<LineBox> sorted = sortLinesTopDown(pageLines);
        int capIdx = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i) == captionLine) { capIdx = i; break; }
        }

        float regionTopPdf = Math.min(pageH - 24f, captionLine.maxY + 10f);
        float regionBottomPdf = footerTop;

        for (int j = capIdx + 1; j < sorted.size(); j++) {
            LineBox above = sorted.get(j);
            if (above.maxY > headerBottom) continue;
            String text = clean(above.text);
            if (isPageMarker(text)) continue;
            if (isNextMediaBoundary(text, ref, captionLine)) {
                regionTopPdf = Math.min(pageH - 24f, above.minY - 6f);
                break;
            }
            regionTopPdf = Math.max(regionTopPdf, Math.min(pageH - 24f, above.maxY + 8f));
        }

        for (int j = capIdx - 1; j >= 0; j--) {
            LineBox below = sorted.get(j);
            String text = clean(below.text);
            if (isNextMediaBoundary(text, ref, captionLine)) {
                regionBottomPdf = Math.max(footerTop, below.maxY + 8f);
                break;
            }
        }

        float minSpan = type.equals("figure") ? pageH * 0.18f : pageH * 0.14f;
        float defaultSpan = type.equals("figure") ? pageH * 0.40f : pageH * 0.50f;
        if (regionTopPdf - regionBottomPdf < minSpan) {
            regionBottomPdf = Math.max(footerTop, regionTopPdf - defaultSpan);
        }

        float minX = pageW;
        float maxX = 0f;
        boolean found = false;
        for (LineBox lb : pageLines) {
            if (lb.maxY <= regionTopPdf + 6f && lb.minY >= regionBottomPdf - 6f) {
                minX = Math.min(minX, lb.minX);
                maxX = Math.max(maxX, lb.maxX);
                found = true;
            }
        }
        if (!found) {
            minX = pageW * 0.05f;
            maxX = pageW * 0.95f;
        }
        minX = Math.max(pageW * 0.03f, minX - margin);
        maxX = Math.min(pageW * 0.97f, maxX + margin);

        int x = Math.max(0, (int) Math.round(minX * scale));
        int y = Math.max(0, (int) Math.round((pageH - regionTopPdf) * scale));
        int w = Math.min((int) (pageW * scale) - x, (int) Math.round((maxX - minX) * scale));
        int h = Math.min((int) (pageH * scale) - y, (int) Math.round((regionTopPdf - regionBottomPdf) * scale));
        if (w < 40 || h < 40) return null;
        return new Rectangle(x, y, w, h);
    }

    static BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    static void writePng(BufferedImage image, Path out) throws IOException {
        ImageIO.write(image, "png", out.toFile());
    }

    static Path findPdftoppm() throws IOException {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                Path win = Paths.get(dir, "pdftoppm.exe");
                if (Files.isRegularFile(win)) return win;
                Path unix = Paths.get(dir, "pdftoppm");
                if (Files.isRegularFile(unix)) return unix;
            }
        }
        Path wingetRoot = Paths.get(System.getenv("LOCALAPPDATA") != null ? System.getenv("LOCALAPPDATA") : "",
                "Microsoft", "WinGet", "Packages");
        if (Files.isDirectory(wingetRoot)) {
            try (var stream = Files.walk(wingetRoot, 5)) {
                Optional<Path> found = stream
                        .filter(p -> "pdftoppm.exe".equalsIgnoreCase(p.getFileName().toString()))
                        .findFirst();
                if (found.isPresent()) return found.get();
            }
        }
        throw new IOException("pdftoppm not found. Install Poppler: winget install oschwartz10612.Poppler");
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    static BufferedImage renderPagePoppler(Path pdfFile, Path pdftoppm, Path tmpDir, int pageNum, int dpi)
            throws IOException {
        String id = "page-" + pageNum + "-" + dpi;
        Path prefix = tmpDir.resolve(id);
        ProcessBuilder pb = new ProcessBuilder(
                pdftoppm.toString(),
                "-f", String.valueOf(pageNum),
                "-l", String.valueOf(pageNum),
                "-png",
                "-r", String.valueOf(dpi),
                "-singlefile",
                pdfFile.toAbsolutePath().toString(),
                prefix.toString());
        pb.redirectErrorStream(true);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to run pdftoppm: " + pdftoppm, e);
        }
        String log;
        try {
            log = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();
            if (code != 0) throw new IOException("pdftoppm page " + pageNum + " failed (" + code + "): " + log);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        Path png = tmpDir.resolve(id + ".png");
        if (!Files.isRegularFile(png)) throw new IOException("pdftoppm output missing: " + png);
        BufferedImage image = ImageIO.read(png.toFile());
        Files.deleteIfExists(png);
        if (image == null) throw new IOException("Failed to read poppler PNG: " + png);
        return image;
    }

    static String cropMedia(Path pdfFile, Path pdftoppm, Path renderTmp, PDDocument doc, List<LineBox> allLines,
                            Path cropsDir,
                            int dpi, MediaRef media, Map<String, PDPage> pageMap) throws IOException {
        LineBox caption = findCaptionLineAnyPage(allLines, media.page, media.caption, media.ref, pageMap);
        if (caption == null) return null;

        int cropPage = caption.page;
        List<LineBox> pageLines = new ArrayList<>();
        for (LineBox lb : allLines) if (lb.page == cropPage) pageLines.add(lb);
        if (pageLines.isEmpty()) return null;

        PDPage page = pageMap.get(String.valueOf(cropPage));
        if (page == null) return null;

        float scale = dpi / 72f;
        Rectangle rect = computeCropRect(page, pageLines, caption, media.type, media.ref, scale);
        if (rect == null) return null;

        BufferedImage full = renderPagePoppler(pdfFile, pdftoppm, renderTmp, cropPage, dpi);
        rect.x = Math.max(0, Math.min(rect.x, full.getWidth() - 1));
        rect.y = Math.max(0, Math.min(rect.y, full.getHeight() - 1));
        rect.width = Math.min(rect.width, full.getWidth() - rect.x);
        rect.height = Math.min(rect.height, full.getHeight() - rect.y);
        if (rect.width < 30 || rect.height < 30) return null;

        BufferedImage crop = full.getSubimage(rect.x, rect.y, rect.width, rect.height);
        crop = copyImage(crop);

        if ("table".equals(media.type)) {
            int nextPageNum = cropPage + 1;
            PDPage nextPage = pageMap.get(String.valueOf(nextPageNum));
            boolean nearFooter = rect.y + rect.height >= full.getHeight() * 0.78f;
            boolean shortCrop = rect.height < full.getHeight() * 0.28f;
            if (nextPage != null && (nearFooter || shortCrop)) {
                List<LineBox> nextLines = new ArrayList<>();
                for (LineBox lb : allLines) if (lb.page == nextPageNum) nextLines.add(lb);
                Rectangle cont = computeContinuationCropRect(nextPage, nextLines, media.ref, scale);
                if (cont != null) {
                    BufferedImage nextFull = renderPagePoppler(pdfFile, pdftoppm, renderTmp, nextPageNum, dpi);
                    cont.x = Math.max(0, Math.min(cont.x, nextFull.getWidth() - 1));
                    cont.y = Math.max(0, Math.min(cont.y, nextFull.getHeight() - 1));
                    cont.width = Math.min(cont.width, nextFull.getWidth() - cont.x);
                    cont.height = Math.min(cont.height, nextFull.getHeight() - cont.y);
                    if (cont.width >= 30 && cont.height >= 30) {
                        BufferedImage contCrop = nextFull.getSubimage(cont.x, cont.y, cont.width, cont.height);
                        crop = stitchVertical(crop, copyImage(contCrop));
                    }
                }
            }
        }

        String fileName = (media.type.equals("figure") ? "fig-" : "tab-") + media.ref.replace('.', '-').replace('/', '-') + ".png";
        Path out = cropsDir.resolve(fileName);
        writePng(crop, out);
        return "assets/plumbing-spec/crops/" + fileName;
    }

    static int appendixRank(String id) {
        if (id.matches("A-[1-5]")) return Integer.parseInt(id.substring(2));
        if (id.startsWith("\u8868-")) return 10;
        if (id.startsWith("\u5716-")) return 11;
        return 20;
    }

    static void postProcessAppendix(Chapter appendix) {
        List<Article> cleaned = new ArrayList<>();
        LinkedHashMap<String, Article> tableArticles = new LinkedHashMap<>();
        LinkedHashMap<String, Article> mainSections = new LinkedHashMap<>();

        for (Article a : appendix.articles) {
            if (a.id.startsWith("\u8868-") || a.id.startsWith("\u5716-")) {
                tableArticles.putIfAbsent(a.id, a);
                continue;
            }
            if (a.id.matches("A-[1-5]")) {
                Article prev = mainSections.get(a.id);
                if (prev == null) {
                    mainSections.put(a.id, a);
                } else if (!isNumericGarbage(a.title) && a.title.length() <= prev.title.length() + 8) {
                    mainSections.put(a.id, a);
                }
                continue;
            }
            if (a.id.startsWith("A-") && isNumericGarbage(a.body) && a.media.isEmpty()) continue;
            if (isNumericGarbage(a.label) && a.media.isEmpty()) continue;
            if (a.id.matches("A-\\d+") && a.media.isEmpty() && isNumericGarbage(a.body)) continue;

            List<MediaRef> spill = new ArrayList<>();
            for (MediaRef m : a.media) {
                String artId = (m.type.equals("figure") ? "\u5716-" : "\u8868-") + m.ref;
                if (!tableArticles.containsKey(artId)) {
                    Article ta = new Article();
                    ta.id = artId;
                    ta.title = m.caption;
                    ta.label = m.caption.length() <= 48 ? m.caption : shortLabel(m.caption);
                    ta.body = m.caption;
                    ta.pages.add(m.page);
                    ta.media.add(m);
                    tableArticles.put(artId, ta);
                }
            }
            a.media.removeIf(m -> {
                String artId = (m.type.equals("figure") ? "\u5716-" : "\u8868-") + m.ref;
                return tableArticles.containsKey(artId) && !tableArticles.get(artId).media.contains(m);
            });
            if (a.id.startsWith("A-") && a.media.isEmpty() && a.body.equals(a.title)) continue;
            cleaned.add(a);
        }

        cleaned.addAll(mainSections.values());
        for (Article ta : tableArticles.values()) cleaned.add(ta);
        cleaned.sort((a, b) -> {
            int ra = appendixRank(a.id);
            int rb = appendixRank(b.id);
            if (ra != rb) return Integer.compare(ra, rb);
            return a.id.compareTo(b.id);
        });
        appendix.articles.clear();
        appendix.articles.addAll(cleaned);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path pdfFile = root.resolve("assets/plumbing-spec.pdf");
        Path pagesDir = root.resolve("assets/plumbing-spec/pages");
        Path cropsDir = root.resolve("assets/plumbing-spec/crops");
        Path textFile = root.resolve("scripts/plumbing-spec-text.txt");
        Path outFile = root.resolve("js/plumbing-spec.js");
        int pageDpi = 144;
        int cropDpi = 216;
        Path pdftoppm = findPdftoppm();
        Path renderTmp = Files.createTempDirectory("plumb-render-");
        System.out.println("Image renderer: poppler " + pdftoppm);

        Files.createDirectories(pagesDir);
        Files.createDirectories(cropsDir);

        List<Integer> pdfPageTimeline = new ArrayList<>();
        List<LineBox> allLines = new ArrayList<>();
        Map<String, PDPage> pageMap = new HashMap<>();

        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            PageLinesStripper stripper = new PageLinesStripper();
            int total = doc.getNumberOfPages();
            System.out.println("PDF pages: " + total);

            for (int i = 0; i < total; i++) {
                int pageNum = i + 1;
                pageMap.put(String.valueOf(pageNum), doc.getPage(i));
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                stripper.getText(doc);
                List<LineBox> pageLines = new ArrayList<>(stripper.lines);
                float pageH = doc.getPage(i).getMediaBox().getHeight();
                if (hasPageFooter(pageLines, pageH)) pdfPageTimeline.add(pageNum);

                allLines.addAll(pageLines);
                stripper.lines.clear();

                Path out = pagesDir.resolve(String.format("page-%03d.png", pageNum));
                BufferedImage image = renderPagePoppler(pdfFile, pdftoppm, renderTmp, pageNum, pageDpi);
                writePng(image, out);
            }

            List<String> lines = Files.readAllLines(textFile, StandardCharsets.UTF_8);
            int startIdx = 0;
            int firstChapterHits = 0;
            Pattern firstChPat = Pattern.compile("\u7b2c\u4e00\u7ae0\\s+\u7e3d\u5247");
            for (int i = 0; i < lines.size(); i++) {
                if (firstChPat.matcher(lines.get(i)).find()) {
                    firstChapterHits++;
                    if (firstChapterHits == 2) { startIdx = i; break; }
                }
            }

            int defaultPdfPage = 2;
            int markerScanStart = startIdx;
            if (startIdx > 0 && isPageMarker(lines.get(startIdx - 1).trim())) markerScanStart = startIdx - 1;
            List<int[]> markerEvents = buildMarkerEvents(lines, markerScanStart, pdfPageTimeline, defaultPdfPage);
            System.out.println("startIdx=" + startIdx + " pdfPages=" + pdfPageTimeline.size()
                    + " markerEvents=" + markerEvents.size());

            Pattern chPat = Pattern.compile("^\u7b2c[\u4e00\u4e8c\u4e09\u56db]\u7ae0\\s*(.+)$");
            Pattern artPat = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)\\s+(.+)$");
            Pattern appendixPat = Pattern.compile("^\u9644\u9304\\s+(.+)$");
            Map<String, Integer> chNum = Map.of("\u4e00", 1, "\u4e8c", 2, "\u4e09", 3, "\u56db", 4);

            List<Chapter> chapters = new ArrayList<>();
            Chapter currentChapter = null;
            Article currentArt = null;
            List<String> bodyLines = new ArrayList<>();
            int artStartLine = startIdx;
            int currentPdfPage = pageAtLine(startIdx, markerEvents, defaultPdfPage);
            int artStartPage = currentPdfPage;
            boolean skipTableRows = false;

            for (int i = startIdx; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                currentPdfPage = pageAtLine(i, markerEvents, defaultPdfPage);

                if (isPageMarker(line)) continue;

                Matcher chM = chPat.matcher(line);
                if (chM.matches()) {
                    flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                    if (currentChapter != null && !currentChapter.articles.isEmpty()) chapters.add(currentChapter);
                    String title = clean(chM.group(1));
                    int num = 0;
                    Matcher numM = Pattern.compile("^\u7b2c([\u4e00\u4e8c\u4e09\u56db])\u7ae0").matcher(line);
                    if (numM.find()) num = chNum.getOrDefault(numM.group(1), 0);
                    currentChapter = new Chapter();
                    currentChapter.id = "PLUMB-ch" + num;
                    currentChapter.icon = chapterIcon(title);
                    currentChapter.title = title;
                    currentChapter.subtitle = clean(line);
                    currentChapter.desc = currentChapter.subtitle;
                    currentArt = null;
                    bodyLines.clear();
                    continue;
                }

                Matcher appM = appendixPat.matcher(line);
                if (appM.matches() && !line.startsWith("\u9644\u9304\u4e4b")) {
                    flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                    if (currentChapter != null && !currentChapter.articles.isEmpty()) chapters.add(currentChapter);
                    currentChapter = new Chapter();
                    currentChapter.id = "PLUMB-appendix";
                    currentChapter.icon = "\uD83D\uDCCA";
                    currentChapter.title = "\u9644\u9304";
                    currentChapter.subtitle = clean(line);
                    currentChapter.desc = currentChapter.subtitle;
                    currentArt = null;
                    bodyLines.clear();
                    continue;
                }

                boolean appendix = currentChapter != null && "PLUMB-appendix".equals(currentChapter.id);

                if (appendix) {
                    String[] mainSec = parseAppendixMainSection(line);
                    if (mainSec != null) {
                        flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                        currentArt = new Article();
                        currentArt.id = mainSec[0];
                        currentArt.title = mainSec[1];
                        bodyLines.clear();
                        artStartPage = currentPdfPage;
                        artStartLine = i;
                        continue;
                    }
                }

                if (appendix && isFigureLine(line)) {
                    flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                    currentArt = new Article();
                    currentArt.id = mediaArtId(line);
                    currentArt.title = clean(line);
                    bodyLines.clear();
                    artStartPage = currentPdfPage;
                    artStartLine = i;
                    MediaRef mr = new MediaRef();
                    mr.type = mediaType(line);
                    mr.ref = mediaRef(line);
                    mr.caption = clean(line);
                    mr.page = currentPdfPage;
                    currentArt.media.add(mr);
                    bodyLines.add(line);
                    skipTableRows = "table".equals(mr.type);
                    continue;
                }

                Matcher artM = artPat.matcher(line);
                if (!appendix && artM.matches()) {
                    flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                    currentArt = new Article();
                    currentArt.id = artM.group(1);
                    currentArt.title = clean(artM.group(2));
                    bodyLines.clear();
                    artStartPage = currentPdfPage;
                    artStartLine = i;
                    if (artM.group(2).length() > 20) bodyLines.add(artM.group(2));
                    continue;
                }

                if (appendix && isAppendixSubSection(line)) {
                    if (currentArt == null) {
                        currentArt = new Article();
                        currentArt.id = "A-text";
                        currentArt.title = "\u9644\u9304\u8aaa\u660e";
                        artStartPage = currentPdfPage;
                        artStartLine = i;
                    }
                    bodyLines.add(line);
                    skipTableRows = false;
                    continue;
                }

                if (currentArt != null) {
                    if (!appendix && isFigureLine(line)) {
                        MediaRef mr = new MediaRef();
                        mr.type = mediaType(line);
                        mr.ref = mediaRef(line);
                        mr.caption = clean(line);
                        mr.page = currentPdfPage;
                        currentArt.media.add(mr);
                        bodyLines.add(line);
                        skipTableRows = "table".equals(mr.type);
                        continue;
                    }
                    if (skipTableRows) {
                        if (isFigureLine(line) || line.startsWith("\u8aaa\u660e") || line.startsWith("\uFEF4\u660e")
                                || line.matches("^\\(\\d+\\).*") || artPat.matcher(line).find()
                                || parseAppendixMainSection(line) != null) {
                            skipTableRows = false;
                        } else if (isNumericGarbage(line)) {
                            continue;
                        }
                    }
                    if (appendix && isNumericGarbage(line)) continue;
                    bodyLines.add(line);
                }
            }
            flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
            if (currentChapter != null && !currentChapter.articles.isEmpty()) chapters.add(currentChapter);

            for (Chapter ch : chapters) {
                if ("PLUMB-appendix".equals(ch.id)) postProcessAppendix(ch);
            }

            int cropCount = 0;
            for (Chapter ch : chapters) {
                for (Article a : ch.articles) {
                    for (MediaRef m : a.media) {
                        m.crop = cropMedia(pdfFile, pdftoppm, renderTmp, doc, allLines, cropsDir, cropDpi, m, pageMap);
                        if (m.crop != null) cropCount++;
                    }
                }
            }
            System.out.println("Media crops: " + cropCount);

            StringBuilder sb = new StringBuilder();
            sb.append("/** Plumbing spec: text + cropped media */\n");
            sb.append("const PLUMBING_SPEC_LAW = {\n");
            sb.append("  id: 'PLUMBING-SPEC',\n");
            sb.append("  pcode: null,\n");
            sb.append("  name: '\u5efa\u7bc9\u7269\u7d66\u6c34\u6392\u6c34\u8a2d\u5099\u8a2d\u8a08\u6280\u8853\u898f\u7bc4',\n");
            sb.append("  shortName: '\u8a2d\u8a08\u6280\u8853\u898f\u7bc4',\n");
            sb.append("  icon: '\uD83D\uDCD8',\n");
            sb.append("  amended: '\u6c11\u570b 111 \u5e74 12 \u6708 29 \u65e5',\n");
            sb.append("  fullUrl: null,\n");
            sb.append("  source: 'pdf',\n");
            sb.append("  pdfFile: 'assets/plumbing-spec.pdf',\n");
            sb.append("  pageDir: 'assets/plumbing-spec/pages',\n");
            sb.append("  cropDir: 'assets/plumbing-spec/crops',\n");
            sb.append("  pageCount: 117,\n");
            sb.append("  categories: [\n");

            for (int ci = 0; ci < chapters.size(); ci++) {
                Chapter ch = chapters.get(ci);
                sb.append("  {\n");
                sb.append("    id: '").append(ch.id).append("',\n");
                sb.append("    icon: '").append(ch.icon).append("',\n");
                sb.append("    title: '").append(escapeJs(ch.title)).append("',\n");
                sb.append("    subtitle: '").append(escapeJs(ch.subtitle)).append("',\n");
                sb.append("    desc: '").append(escapeJs(ch.desc)).append("',\n");
                sb.append("    articles: [\n");
                for (int ai = 0; ai < ch.articles.size(); ai++) {
                    Article a = ch.articles.get(ai);
                    sb.append("      { art: '").append(a.id).append("', label: '")
                            .append(escapeJs(a.label)).append("', body: '")
                            .append(escapeJs(a.body)).append("', pages: [")
                            .append(a.pages.stream().map(String::valueOf).reduce((x, y) -> x + ", " + y).orElse(""))
                            .append("], media: [");
                    for (int mi = 0; mi < a.media.size(); mi++) {
                        MediaRef m = a.media.get(mi);
                        sb.append("{ type: '").append(m.type).append("', ref: '")
                                .append(escapeJs(m.ref)).append("', caption: '")
                                .append(escapeJs(m.caption)).append("', page: ")
                                .append(m.page);
                        if (m.crop != null) sb.append(", crop: '").append(escapeJs(m.crop)).append("'");
                        sb.append(" }");
                        if (mi < a.media.size() - 1) sb.append(", ");
                    }
                    sb.append("] }");
                    if (ai < ch.articles.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("    ],\n");
                sb.append("  }");
                if (ci < chapters.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ],\n");
            sb.append("};\n");

            Files.write(outFile, sb.toString().getBytes(StandardCharsets.UTF_8));
            int totalArts = chapters.stream().mapToInt(c -> c.articles.size()).sum();
            System.out.println("Chapters: " + chapters.size() + ", Articles: " + totalArts);
            System.out.println("Output: " + outFile);
        } finally {
            deleteTree(renderTmp);
        }
    }
}
