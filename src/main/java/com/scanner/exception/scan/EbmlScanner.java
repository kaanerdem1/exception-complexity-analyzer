package com.scanner.exception.scan;

import com.scanner.exception.config.Config;
import com.scanner.exception.model.EbmlFile;
import com.scanner.exception.model.RepoInfo;
import com.scanner.exception.model.RepoResult;
import com.scanner.exception.model.ScanError;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Repodaki ekran, region ve Jasper rapor dosyalarını bulur ve sınıflandırır:
 *   Region : adı RG_ ile başlayan veya ebml.region paketi (ya da alt paketleri) altındaki .ebml dosyaları
 *   Ekran  : ebml.page paketi (ya da alt paketleri) altındaki .ebml dosyaları
 *   Popup  : ebml.popup paketi (ya da alt paketleri) altındaki .ebml dosyaları
 *   Rapor  : ebml.report paketi (ya da alt paketleri) altındaki .dsxml dosyaları
 *   Process: process klasörü altındaki 250001-XXX.par veya 250001-XXX klasörlerinde bulunan processdefinition.xml
 *            dosyaları (.par eki zorunlu değil); numara klasör adından, ad dosyadaki label özelliğinden alınır
 * Region kuralı önce uygulanır: ebml.page veya ebml.popup altında olup adı RG_ ile başlayan dosya region sayılır.
 * Kurallara uymayan .ebml / .dsxml dosyaları "sınıflandırılmadı" olarak sadece rapora yazılır.
 */
public final class EbmlScanner {

    private static final String EBML = ".ebml";
    private static final String DSXML = ".dsxml";
    private static final String PROCESS_DEFINITION = "processdefinition.xml";
    /** Process klasörü: 250001-RISM.par veya 250001-RISM -> 250001 ve RISM (.par eki olmayabilir) */
    private static final Pattern PROCESS_FOLDER =
            Pattern.compile("(\\d+)(?:[-_](.*?))?(?:\\.par)?", Pattern.CASE_INSENSITIVE);

    private final Config cfg;
    private final Config.Ebml ebml;

    public EbmlScanner(Config cfg) {
        this.cfg = cfg;
        this.ebml = cfg.ebml;
    }

    public void scan(RepoInfo repo, RepoResult result) {
        final Path root = repo.localPath;
        final List<Path> files = new ArrayList<Path>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)) {
                        String n = dir.getFileName().toString();
                        // target, bin, classes gibi derleme klasörlerindeki kopyalar iki kez sayılmasın
                        if (n.startsWith(".") || cfg.excludeDirs.contains(n)) return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(EBML) || n.endsWith(DSXML) || n.equals(PROCESS_DEFINITION)) files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            result.errors.add(new ScanError(repo, ScanError.Stage.EBML_LISTING, "", e.getMessage()));
            return;
        }
        Collections.sort(files);

        Map<Path, String> moduleCache = new HashMap<Path, String>();
        for (Path f : files) {
            String rel = ModuleResolver.relative(root, f);
            String module = ModuleResolver.moduleOf(root, f.getParent(), moduleCache);
            EbmlFile e = classify(repo, rel, module);
            if (e.kind == EbmlFile.Kind.PROCESS) {
                try {
                    String[] info = processInfo(f);
                    e.processName = info[0];
                    e.processCode = info[1];
                    if (e.processName.isEmpty()) {
                        result.errors.add(new ScanError(repo, ScanError.Stage.PROCESS_LABEL, rel, "label özelliği bulunamadı"));
                    }
                } catch (IOException | XMLStreamException ex) {
                    result.errors.add(new ScanError(repo, ScanError.Stage.PROCESS_LABEL, rel, ex.getMessage()));
                }
            }
            result.ebmlFiles.add(e);
        }
    }

    /**
     * processdefinition.xml içindeki process adını (label) ve kodunu (name) okur: kök elemanın
     * özellikleri, kökte label yoksa adında "process" geçen ilk elemanınkiler. Dönüş: {label, name}
     */
    static String[] processInfo(Path file) throws IOException, XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        InputStream in = Files.newInputStream(file);
        try {
            XMLStreamReader r = f.createXMLStreamReader(in);
            try {
                boolean root = true;
                while (r.hasNext()) {
                    if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                    String label = r.getAttributeValue(null, "label");
                    boolean processElement = r.getLocalName().toLowerCase(Locale.ROOT).contains("process");
                    if (label != null && (root || processElement)) {
                        String name = r.getAttributeValue(null, "name");
                        return new String[]{label.trim(), name == null ? "" : name.trim()};
                    }
                    root = false;
                }
                return new String[]{"", ""};
            } finally {
                r.close();
            }
        } finally {
            in.close();
        }
    }

    /** Dosyayı yol bilgisine göre sınıflandırır; dosyanın içi okunmaz. */
    EbmlFile classify(RepoInfo repo, String rel, String module) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        String lowerName = name.toLowerCase(Locale.ROOT);
        String dir = rel.lastIndexOf('/') < 0 ? "" : rel.substring(0, rel.lastIndexOf('/'));
        List<String> segments = new ArrayList<String>();
        for (String s : dir.split("/")) {
            if (!s.isEmpty()) segments.add(s.toLowerCase(Locale.ROOT));
        }

        EbmlFile e = new EbmlFile();
        e.projectKey = repo.projectKey;
        e.repo = repo.slug;
        e.module = module;
        e.file = rel;
        e.link = repo.fileLink(rel);
        e.fileName = ebml.fileNameWithExtension || name.indexOf('.') < 0 ? name : name.substring(0, name.lastIndexOf('.'));
        e.packageName = packageOf(dir, module);
        e.projectName = projectNameOf(repo, module);
        e.projectNameCandidates.add(e.projectName);
        // Ad bulunamazsa denenecek ikinci ad: repo adı için slug, Bitbucket projesi için proje anahtarı
        String alt = ebml.projectNameSource == Config.Ebml.ProjectNameSource.REPO ? repo.slug
                : ebml.projectNameSource == Config.Ebml.ProjectNameSource.BITBUCKET_PROJECT ? repo.projectKey : null;
        if (alt != null && !alt.isEmpty() && !alt.equals(e.projectName)) e.projectNameCandidates.add(alt);

        if (lowerName.equals(PROCESS_DEFINITION)) {
            // process/250001-RISM.par/processdefinition.xml veya process/250001-RISM/processdefinition.xml
            int n = segments.size();
            String folder = n == 0 ? "" : dir.substring(dir.lastIndexOf('/') + 1);
            Matcher m = PROCESS_FOLDER.matcher(folder);
            boolean underProcess = n >= 2 && segments.get(n - 2).equalsIgnoreCase(ebml.processDir);
            if (m.matches() && underProcess && m.group(1).length() <= 18) {
                e.kind = EbmlFile.Kind.PROCESS;
                // PAR: klasör .par ekli, FOLDER: .par eki olmayan process klasörü
                e.rule = folder.toLowerCase(Locale.ROOT).endsWith(".par") ? "PAR" : "FOLDER";
                e.processId = Long.valueOf(m.group(1));
                e.processShortName = m.group(2) == null ? "" : m.group(2);
                e.fileName = folder;
                e.packageName = "";
            } else {
                e.kind = EbmlFile.Kind.UNCLASSIFIED;
                e.rule = "processdefinition.xml, " + ebml.processDir + "/<numara>[-<ad>][.par] altında değil";
            }
        } else if (lowerName.endsWith(EBML)) {
            boolean prefix = !ebml.regionPrefix.isEmpty()
                    && lowerName.startsWith(ebml.regionPrefix.toLowerCase(Locale.ROOT));
            boolean regionPkg = under(segments, ebml.regionPackage);
            if (prefix || regionPkg) {
                e.kind = EbmlFile.Kind.REGION;
                e.rule = prefix && regionPkg ? "PREFIX+PACKAGE" : prefix ? "PREFIX" : "PACKAGE";
            } else if (under(segments, ebml.pagePackage)) {
                e.kind = EbmlFile.Kind.SCREEN;
                e.rule = "PACKAGE";
            } else if (under(segments, ebml.popupPackage)) {
                e.kind = EbmlFile.Kind.POPUP;
                e.rule = "PACKAGE";
            } else {
                e.kind = EbmlFile.Kind.UNCLASSIFIED;
                e.rule = "Beklenen paketlerde değil";
            }
        } else {
            if (under(segments, ebml.reportPackage)) {
                e.kind = EbmlFile.Kind.REPORT;
                e.rule = "PACKAGE";
            } else {
                e.kind = EbmlFile.Kind.UNCLASSIFIED;
                e.rule = ".dsxml, ebml.report altında değil";
            }
        }
        return e;
    }

    /** Klasör yolunda paket parçaları art arda geçiyor mu: [.., ebml, region, ..] (alt paketler dahil) */
    static boolean under(List<String> dirSegments, List<String> pkg) {
        if (pkg.isEmpty()) return false;
        outer:
        for (int i = 0; i + pkg.size() <= dirSegments.size(); i++) {
            for (int k = 0; k < pkg.size(); k++) {
                if (!dirSegments.get(i + k).equals(pkg.get(k))) continue outer;
            }
            return true;
        }
        return false;
    }

    /** "modul/src/main/java/tr/com/x/ebml/page" -> "tr.com.x.ebml.page" */
    String packageOf(String dir, String module) {
        String d = dir;
        if (!ModuleResolver.ROOT.equals(module) && (d.equals(module) || d.startsWith(module + "/"))) {
            d = d.length() == module.length() ? "" : d.substring(module.length() + 1);
        }
        for (String rootDir : ebml.sourceRoots) {
            String r = rootDir.replace('\\', '/').replaceAll("^/+|/+$", "");
            if (d.equals(r)) return "";
            if (d.startsWith(r + "/")) {
                d = d.substring(r.length() + 1);
                break;
            }
        }
        return d.replace('/', '.');
    }

    /** env.project tablosunda aranacak ana proje adı */
    String projectNameOf(RepoInfo repo, String module) {
        switch (ebml.projectNameSource) {
            case REPO_SLUG:
                return repo.slug;
            case MODULE:
                if (!ModuleResolver.ROOT.equals(module)) return module.substring(module.lastIndexOf('/') + 1);
                return repo.name == null || repo.name.isEmpty() ? repo.slug : repo.name;
            case BITBUCKET_PROJECT:
                return repo.projectName == null || repo.projectName.isEmpty() ? repo.projectKey : repo.projectName;
            case REPO:
            default:
                return repo.name == null || repo.name.isEmpty() ? repo.slug : repo.name;
        }
    }
}
