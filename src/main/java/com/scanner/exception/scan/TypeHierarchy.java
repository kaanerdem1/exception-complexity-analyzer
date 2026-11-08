package com.scanner.exception.scan;

import com.scanner.exception.config.Config;
import com.scanner.exception.model.RepoInfo;
import com.scanner.exception.model.SubclassFinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tüm repolardaki sınıf başlıklarından aranan exception'ların alt sınıflarını bulur (scan.global.hierarchy=true).
 * Böylece bir repoda (ör. ortak kütüphane) tanımlanan alt sınıf, onu kullanan diğer repolarda da aranır;
 * repolar arası zincirler (A'da X extends CustomException, B'de Y extends X) de çözülür.
 */
public final class TypeHierarchy {

    /** Bir sınıfın sadece extends bilgisi; dosyanın import özeti yerine olası üst sınıf adları tutulur. */
    public static final class Header {
        final String fqn;
        final String file;
        final String module;
        final int line;
        /** Her extends için: [kodda yazılan ad, olası tam adlar...] */
        final List<String[]> parents;

        Header(String fqn, String file, String module, int line, List<String[]> parents) {
            this.fqn = fqn;
            this.file = file;
            this.module = module;
            this.line = line;
            this.parents = parents;
        }
    }

    /** Hiyerarşi sonucu: aranacak tüm sınıflar ve her reponun kendi tanımladığı alt sınıflar */
    public static final class Result {
        public final Set<String> targets;
        public final Map<RepoInfo, List<SubclassFinding>> subclasses;

        Result(Set<String> targets, Map<RepoInfo, List<SubclassFinding>> subclasses) {
            this.targets = targets;
            this.subclasses = subclasses;
        }

        public int subclassCount() {
            int n = 0;
            for (List<SubclassFinding> l : subclasses.values()) n += l.size();
            return n;
        }
    }

    private TypeHierarchy() { }

    public static Result resolve(Config cfg, Map<RepoInfo, List<Header>> headers) {
        Set<String> targets = new LinkedHashSet<String>(cfg.exceptionClasses);
        Map<RepoInfo, List<SubclassFinding>> found = new LinkedHashMap<RepoInfo, List<SubclassFinding>>();
        for (RepoInfo r : headers.keySet()) found.put(r, new ArrayList<SubclassFinding>());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<RepoInfo, List<Header>> e : headers.entrySet()) {
                RepoInfo repo = e.getKey();
                for (Header h : e.getValue()) {
                    if (targets.contains(h.fqn) || cfg.ignoreClasses.contains(h.fqn)) continue;
                    String parent = parentIn(h, targets, cfg.lenientMatch);
                    if (parent == null) continue;
                    targets.add(h.fqn);
                    changed = true;
                    found.get(repo).add(finding(repo, h, parent));
                }
            }
        }
        // Aynı FQCN birden fazla repoda tanımlıysa ilk turda biri hedef olunca diğeri atlanmış olur; onları da listele
        for (Map.Entry<RepoInfo, List<Header>> e : headers.entrySet()) {
            Set<String> listed = new java.util.HashSet<String>();
            for (SubclassFinding sf : found.get(e.getKey())) listed.add(sf.className);
            for (Header h : e.getValue()) {
                if (listed.contains(h.fqn) || !targets.contains(h.fqn) || cfg.exceptionClasses.contains(h.fqn)) continue;
                String parent = parentIn(h, targets, cfg.lenientMatch);
                if (parent != null && !parent.equals(h.fqn)) found.get(e.getKey()).add(finding(e.getKey(), h, parent));
            }
            found.get(e.getKey()).sort(java.util.Comparator.comparing((SubclassFinding sf) -> sf.file)
                    .thenComparingInt(sf -> sf.line));
        }
        return new Result(targets, found);
    }

    private static String parentIn(Header h, Set<String> targets, boolean lenient) {
        for (String[] p : h.parents) {
            if (lenient) {
                for (String t : targets) {
                    if (t.equals(p[0]) || t.endsWith("." + p[0])) return t;
                }
            } else {
                for (int i = 1; i < p.length; i++) {
                    if (targets.contains(p[i])) return p[i];
                }
            }
        }
        return null;
    }

    private static SubclassFinding finding(RepoInfo repo, Header h, String parent) {
        SubclassFinding sf = new SubclassFinding();
        sf.projectKey = repo.projectKey;
        sf.projectName = repo.projectName;
        sf.repo = repo.slug;
        sf.module = h.module;
        sf.file = h.file;
        sf.testCode = JavaSourceScanner.isTestPath(h.file);
        sf.className = h.fqn;
        sf.member = "";
        sf.line = h.line;
        sf.link = repo.fileLink(h.file, h.line);
        sf.parent = parent;
        return sf;
    }
}
