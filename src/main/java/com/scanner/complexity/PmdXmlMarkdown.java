package com.scanner.complexity;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Türkçe PMD karmaşıklık Markdown raporu. */
final class PmdXmlMarkdown {

    private static final Pattern CC_MSG =
            Pattern.compile("cyclomatic complexity of (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COG_MSG =
            Pattern.compile("cognitive complexity of (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NPATH_MSG =
            Pattern.compile("NPath complexity of (\\d+)", Pattern.CASE_INSENSITIVE);

    private PmdXmlMarkdown() {
    }

    static String render(Path pmdXml) throws IOException {
        Document doc = parse(pmdXml);
        List<ViolationRow> rows = collectRows(doc);
        Map<String, MethodAgg> byMethod = aggregate(rows);
        List<MethodAgg> methods = new ArrayList<>(byMethod.values());
        methods.sort(Comparator.comparingInt(MethodAgg::sortCc).reversed());

        NodeList files = doc.getElementsByTagName("file");

        StringBuilder md = new StringBuilder();
        md.append("# PMD karmaşıklık raporu\n\n");
        md.append("Bu rapor **PMD** aracının üç karmaşıklık kuralını çalıştırır. ");
        md.append("Eşikler düşük tutulduğu için neredeyse **her metod** listede yer alır.\n\n");

        md.append("## Metrikler ne anlama geliyor?\n\n");
        md.append("1. **Dallanma karmaşıklığı (CC — Cyclomatic Complexity)**  \n");
        md.append("   Metodda kaç bağımsız yürütme yolu var? (if, döngü, case, `&&` / `||` …)  \n");
        md.append("   *Yüksek CC = daha fazla test senaryosu gerekir.*\n\n");
        md.append("2. **Bilişsel karmaşıklık (Cognitive Complexity)**  \n");
        md.append("   Kodu **insanın okuma zorluğu**. İç içe yapı ve `else if` zincirleri daha çok puan artırır.  \n");
        md.append("   *Aynı CC’ye sahip iki metoddan biri cognitive olarak daha ağır olabilir.*\n\n");
        md.append("3. **NPath karmaşıklığı**  \n");
        md.append("   Metodun **olası yürütme yolu sayısının** üst sınırı (kombinatorik).  \n");
        md.append("   *Çok yüksek NPath = patlama riski; büyük metodlarda astronomik çıkabilir.*\n\n");

        md.append("## Özet\n\n");
        md.append("| Alan | Değer |\n|------|-------|\n");
        md.append("| Kaynak (XML) | `").append(pmdXml.toAbsolutePath().normalize()).append("` |\n");
        md.append("| Java dosyası | ").append(files.getLength()).append(" |\n");
        md.append("| Metod (benzersiz) | ").append(methods.size()).append(" |\n");
        md.append("| PMD uyarı satırı | ").append(rows.size()).append(" (≈ 3 kural × metod) |\n\n");

        md.append("## Metod listesi (dallanma CC’ye göre azalan)\n\n");
        md.append("| Dallanma (CC) | Bilişsel | NPath | Sınıf | Metod | Dosya | Satır |\n");
        md.append("|-------------:|---------:|------:|-------|-------|-------|------:|\n");
        for (MethodAgg m : methods) {
            md.append("| ").append(dash(m.cc)).append(" | ").append(dash(m.cognitive))
                    .append(" | ").append(dash(m.npath)).append(" | `").append(m.className)
                    .append("` | `").append(m.method).append("` | `").append(m.file).append("` | ")
                    .append(m.minLine == Integer.MAX_VALUE ? "—" : m.minLine).append(" |\n");
        }
        md.append("\n");

        md.append("## En yüksek dallanma (CC) — ilk 10\n\n");
        methods.stream().limit(10).forEach(m -> md.append("- **").append(m.className).append(".")
                .append(m.method).append("** → CC ").append(m.cc)
                .append(m.cognitive != null ? ", bilişsel " + m.cognitive : "")
                .append(m.npath != null ? ", NPath " + m.npath : "")
                .append(" (`").append(m.file).append("`)\n"));

        md.append("\n---\n");
        md.append("*Parser tarafındaki risk skoru için `parser-raporu.md` dosyasına bakın. ");
        md.append("CC/cognitive parser taramasında PMD metrikleri ile hizalanır; bu rapor bağımsız PMD koşusudur.*\n");
        return md.toString();
    }

    private static List<ViolationRow> collectRows(Document doc) {
        List<ViolationRow> rows = new ArrayList<>();
        NodeList files = doc.getElementsByTagName("file");
        for (int f = 0; f < files.getLength(); f++) {
            Element fileEl = (Element) files.item(f);
            String shortFile = shortenPath(fileEl.getAttribute("name"));
            NodeList violations = fileEl.getElementsByTagName("violation");
            for (int v = 0; v < violations.getLength(); v++) {
                Element viol = (Element) violations.item(v);
                rows.add(new ViolationRow(
                        shortFile,
                        viol.getAttribute("class"),
                        viol.getAttribute("method"),
                        viol.getAttribute("rule"),
                        Integer.parseInt(viol.getAttribute("beginline")),
                        text(viol)));
            }
        }
        return rows;
    }

    private static Map<String, MethodAgg> aggregate(List<ViolationRow> rows) {
        Map<String, MethodAgg> byMethod = new LinkedHashMap<>();
        for (ViolationRow row : rows) {
            String key = row.file + "|" + row.className + "|" + row.method;
            byMethod.computeIfAbsent(key, k -> new MethodAgg(row.file, row.className, row.method)).apply(row);
        }
        return byMethod;
    }

    private static Document parse(Path pmdXml) throws IOException {
        try (InputStream in = Files.newInputStream(pmdXml)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(in);
        } catch (Exception e) {
            throw new IOException("PMD XML okunamadı: " + e.getMessage(), e);
        }
    }

    private static String text(Element el) {
        return el.getTextContent().trim().replaceAll("\\s+", " ");
    }

    private static String shortenPath(String path) {
        int idx = path.indexOf("mock-modules/");
        return idx >= 0 ? path.substring(idx) : path;
    }

    private static String dash(Integer v) {
        return v == null ? "—" : String.valueOf(v);
    }

    private record ViolationRow(String file, String className, String method, String rule, int line, String message) {
    }

    private static final class MethodAgg {
        final String file;
        final String className;
        final String method;
        Integer cc;
        Integer cognitive;
        Integer npath;
        int minLine = Integer.MAX_VALUE;

        MethodAgg(String file, String className, String method) {
            this.file = file;
            this.className = className;
            this.method = method;
        }

        void apply(ViolationRow row) {
            minLine = Math.min(minLine, row.line);
            Matcher m;
            if ("CyclomaticComplexity".equals(row.rule) && (m = CC_MSG.matcher(row.message)).find()) {
                cc = Integer.parseInt(m.group(1));
            }
            if ("CognitiveComplexity".equals(row.rule) && (m = COG_MSG.matcher(row.message)).find()) {
                cognitive = Integer.parseInt(m.group(1));
            }
            if ("NPathComplexity".equals(row.rule) && (m = NPATH_MSG.matcher(row.message)).find()) {
                npath = Integer.parseInt(m.group(1));
            }
        }

        int sortCc() {
            return cc == null ? -1 : cc;
        }
    }
}
