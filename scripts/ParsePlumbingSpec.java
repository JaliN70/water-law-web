import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ParsePlumbingSpec {
    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    static String shortLabel(String text) {
        text = clean(text);
        if (text.length() <= 28) return text;
        return text.substring(0, 28) + "\u2026";
    }

    static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    static class Article {
        String id, title, label, body;
    }

    static class Chapter {
        String id, icon, title, subtitle, desc;
        List<Article> articles = new ArrayList<>();
    }

    static String chapterIcon(String title) {
        if (title.contains("\u7e3d\u5247")) return "\uD83D\uDCCB";
        if (title.contains("\u914d\u7ba1")) return "\uD83D\uDCD0";
        if (title.contains("\u7d66\u6c34")) return "\uD83D\uDCA7";
        if (title.contains("\u6392\u6c34")) return "\uD83C\uDF27\uFE0F";
        return "\uD83D\uDCCA";
    }

    static boolean isPageMarker(String line) {
        return line.matches("\\d+\\s*-\\s*\\d+")
                || line.matches("\u9644\u9304-\\s*\\d+");
    }

    static void flushArticle(Chapter chapter, Article art, List<String> bodyLines) {
        if (chapter == null || art == null) return;
        String body = clean(String.join(" ", bodyLines));
        if (body.isEmpty()) body = art.title;
        String labelSrc = art.title.isEmpty() ? body : art.title + " " + body;
        Article a = new Article();
        a.id = art.id;
        a.title = art.title;
        a.label = shortLabel(labelSrc);
        a.body = body;
        chapter.articles.add(a);
        bodyLines.clear();
    }

    public static void main(String[] args) throws Exception {
        Path scriptDir = Paths.get(args.length > 0 ? args[0] : ".");
        Path textFile = scriptDir.resolve("plumbing-spec-text.txt");
        Path outFile = scriptDir.getParent().resolve("js/plumbing-spec.js");

        List<String> lines = Files.readAllLines(textFile, StandardCharsets.UTF_8);
        int startIdx = 0;
        int firstChapterHits = 0;
        Pattern firstChPat = Pattern.compile("\u7b2c\u4e00\u7ae0\\s+\u7e3d\u5247");
        for (int i = 0; i < lines.size(); i++) {
            if (firstChPat.matcher(lines.get(i)).find()) {
                firstChapterHits++;
                if (firstChapterHits == 2) {
                    startIdx = i;
                    break;
                }
            }
        }
        if (startIdx == 0) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).matches("\u7b2c\u4e00\u7ae0\\s+\u7e3d\u5247.*") && i > 20) {
                    startIdx = i;
                    break;
                }
            }
        }

        Pattern chPat = Pattern.compile("^\u7b2c[\u4e00\u4e8c\u4e09\u56db]\u7ae0\\s*(.+)$");
        Pattern artPat = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)\\s+(.+)$");
        Pattern appMainPat = Pattern.compile("^(\\d+)\\s+(.+)$");
        Pattern appendixPat = Pattern.compile("^\u9644\u9304\\s+(.+)$");

        Map<String, Integer> chNum = new LinkedHashMap<>();
        chNum.put("\u4e00", 1);
        chNum.put("\u4e8c", 2);
        chNum.put("\u4e09", 3);
        chNum.put("\u56db", 4);

        List<Chapter> chapters = new ArrayList<>();
        Chapter currentChapter = null;
        Article currentArt = null;
        List<String> bodyLines = new ArrayList<>();

        for (int i = startIdx; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || isPageMarker(line)) continue;

            Matcher chM = chPat.matcher(line);
            if (chM.matches()) {
                flushArticle(currentChapter, currentArt, bodyLines);
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
                flushArticle(currentChapter, currentArt, bodyLines);
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

            Matcher artM = artPat.matcher(line);
            if (artM.matches()) {
                flushArticle(currentChapter, currentArt, bodyLines);
                currentArt = new Article();
                currentArt.id = artM.group(1);
                currentArt.title = clean(artM.group(2));
                bodyLines.clear();
                if (artM.group(2).length() > 15) bodyLines.add(artM.group(2));
                continue;
            }

            if (currentChapter != null && "PLUMB-appendix".equals(currentChapter.id)) {
                Matcher appMainM = appMainPat.matcher(line);
                if (appMainM.matches() && !line.matches("\\d+\\.\\d+.*")) {
                    flushArticle(currentChapter, currentArt, bodyLines);
                    currentArt = new Article();
                    currentArt.id = "A-" + appMainM.group(1);
                    currentArt.title = clean(appMainM.group(2));
                    bodyLines.clear();
                    continue;
                }
            }

            if (currentArt != null) {
                bodyLines.add(line);
            }
        }
        flushArticle(currentChapter, currentArt, bodyLines);
        if (currentChapter != null && !currentChapter.articles.isEmpty()) chapters.add(currentChapter);

        StringBuilder sb = new StringBuilder();
        sb.append("/** Plumbing spec parsed from PDF */\n");
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
                        .append(escapeJs(a.body)).append("' }");
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
        int total = chapters.stream().mapToInt(c -> c.articles.size()).sum();
        System.out.println("Chapters: " + chapters.size() + ", Articles: " + total);
        System.out.println("Output: " + outFile + " (" + Files.size(outFile) + " bytes)");
    }
}
