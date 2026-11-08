package com.scanner.exception.scan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import com.scanner.exception.config.Config;
import com.scanner.exception.model.CatchFinding;
import com.scanner.exception.model.Located;
import com.scanner.exception.model.MemberRef;
import com.scanner.exception.model.RepoInfo;
import com.scanner.exception.model.RepoResult;
import com.scanner.exception.model.ScanError;
import com.scanner.exception.model.SubclassFinding;
import com.scanner.exception.model.UsageFinding;
import com.scanner.exception.repo.BitbucketClient;
import com.scanner.exception.scan.ArgumentClassifier.ArgKind;
import com.scanner.exception.scan.TypeMatcher.ImportContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Bir reponun Java kaynaklarını ayrıştırıp hedef exception kullanımlarını bulur. */
public final class JavaSourceScanner {

    private static final Pattern TEST_PATH = Pattern.compile("(^|.*/)(src/test|test|tests)/.*");

    private final Config cfg;
    private final CatchClassifier catchClassifier;

    public JavaSourceScanner(Config cfg) {
        this.cfg = cfg;
        this.catchClassifier = new CatchClassifier(cfg.swallowedLogNames);
    }

    /** Alt sınıf tespiti için bir sınıfın sadece başlık bilgisi (ayrıştırılmış dosya saklanmaz). */
    private static final class ClassHeader {
        Path file;
        String fqn;
        int line;
        ImportContext ctx;
        final List<String> extendsWritten = new ArrayList<String>();
    }

    /**
     * Bellek kullanımını düşük tutmak için iki geçişte tarar ve hiçbir dosyanın ayrıştırılmış hâlini saklamaz:
     * 1. geçiş: "extends" ve aranan sınıflardan birinin adını içeren dosyalardan sınıf başlıklarını toplayıp alt sınıfları bulur.
     * 2. geçiş: sadece aranan sınıf veya metot adlarını metin olarak içeren dosyaları ayrıştırıp kullanımları bulur.
     */
    public RepoResult scan(RepoInfo repo) {
        return scan(repo, null);
    }

    /**
     * @param hierarchy tüm repolardan çıkarılmış sınıf hiyerarşisi (scan.global.hierarchy=true); verilirse
     *                  1. geçiş yapılmaz, aranacak sınıflar ve bu reponun alt sınıfları oradan alınır
     */
    public RepoResult scan(RepoInfo repo, TypeHierarchy.Result hierarchy) {
        RepoResult result = new RepoResult(repo);
        Path root = repo.localPath;

        List<Path> files;
        try {
            files = collectJavaFiles(root);
        } catch (IOException e) {
            result.errors.add(new ScanError(repo, ScanError.Stage.FILE_LISTING, "", e.getMessage()));
            return result;
        }
        result.javaFiles = files.size();

        Map<Path, String> moduleCache = new ConcurrentHashMap<Path, String>();
        List<Path> scannable = scannable(repo, files, result);

        // ---- 1. geçiş: aranan sınıfların alt sınıfları.
        // "class X extends Y" ile Y'ye bağlanabilmek için dosyada Y'nin adı geçmek zorundadır. Bu yüzden
        // "extends" içeren dosyalar bir kez okunup içlerindeki isimlerin dizini çıkarılır; sadece bilinen
        // hedeflerden birinin adını içeren dosyalar ayrıştırılır. Yeni alt sınıf bulundukça onların adlarıyla
        // dizine tekrar bakılır (X'ten türeyenler için). Sonuç tüm dosyaları ayrıştırmakla aynıdır.
        Set<String> targets = new LinkedHashSet<String>(cfg.exceptionClasses);
        if (hierarchy != null) {
            targets = hierarchy.targets;
            List<SubclassFinding> own = hierarchy.subclasses.get(repo);
            if (own != null) result.subclasses.addAll(own);
        } else if (!targets.isEmpty()) {
            Map<String, List<Integer>> index = identifierIndex(scannable);
            List<ClassHeader> headers = new ArrayList<ClassHeader>();
            Set<Integer> parsed = new HashSet<Integer>();
            Set<String> searchedNames = new HashSet<String>();
            while (true) {
                java.util.TreeSet<Integer> candidates = new java.util.TreeSet<Integer>();
                for (String t : targets) {
                    String n = TypeMatcher.simpleName(t);
                    if (!searchedNames.add(n)) continue;
                    List<Integer> hits = index.get(n);
                    if (hits != null) for (Integer i : hits) if (parsed.add(i)) candidates.add(i);
                }
                if (candidates.isEmpty()) break;
                List<Path> batch = new ArrayList<Path>(candidates.size());
                for (Integer i : candidates) batch.add(scannable.get(i));
                List<ClassHeader> found = parallel(batch, f -> classHeaders(f, readSource(f)));
                headers.addAll(found);
                resolveTargets(repo, root, headers, targets, result, moduleCache);
            }
            result.subclasses.sort(Comparator.comparing((SubclassFinding sf) -> sf.file)
                    .thenComparingInt(sf -> sf.line));
        }

        // Kullanım içerebilecek dosyaları ayrıştırmadan önce metin olarak ayıklamak için anahtar kelimeler
        Set<String> keywords = new LinkedHashSet<String>();
        for (String t : targets) keywords.add(TypeMatcher.simpleName(t));
        List<String> configuredNames = new ArrayList<String>();
        for (String c : cfg.exceptionClasses) configuredNames.add(TypeMatcher.simpleName(c));

        // ---- 2. geçiş: kullanımlar. Dosyalar paralel ayrıştırılır; her dosyanın sonucu ayrı toplanıp birleştirilir.
        final Set<String> finalTargets = targets;
        final boolean allSwallowed = cfg.collectAllSwallowed();
        List<RepoResult> parts = parallel(scannable, f -> {
            RepoResult part = new RepoResult(repo);
            String rel = relative(root, f);
            try {
                String source = readSource(f);
                if (containsAny(source, configuredNames)) part.filesMentioningTarget++;
                // scan.swallowed.scope=ALL: aranan sınıf geçmese de catch içeren her dosya ayrıştırılır
                if (!containsAny(source, keywords) && !(allSwallowed && source.contains("catch"))) {
                    return part.filesMentioningTarget > 0 ? part : null;
                }

                ParseResult<CompilationUnit> pr = parse(source);
                if (!pr.isSuccessful()) {
                    part.parseFailures++;
                    String msg = pr.getProblems().isEmpty() ? "Ayrıştırılamadı" : pr.getProblem(0).getVerboseMessage();
                    part.errors.add(new ScanError(repo, ScanError.Stage.PARSE, rel, BitbucketClient.abbreviate(msg, 400)));
                }
                if (pr.getResult().isPresent()) {
                    String module = moduleOf(root, f.getParent(), moduleCache);
                    scanUnit(pr.getResult().get(), repo, rel, module, TEST_PATH.matcher(rel).matches(), finalTargets, part);
                }
            } catch (IOException | RuntimeException e) {
                part.parseFailures++;
                part.errors.add(new ScanError(repo, ScanError.Stage.READ, rel, String.valueOf(e.getMessage())));
            }
            return part;
        });
        for (RepoResult part : parts) {
            result.filesMentioningTarget += part.filesMentioningTarget;
            result.parseFailures += part.parseFailures;
            result.usages.addAll(part.usages);
            result.swallowed.addAll(part.swallowed);
            result.errors.addAll(part.errors);
        }

        Collections.sort(result.usages, (a, b) -> a.file.equals(b.file)
                ? Integer.compare(a.line, b.line) : a.file.compareTo(b.file));
        return result;
    }

    /** Boyut sınırını aşan dosyalar hariç Java dosyaları; result verilirse atlananlar hata olarak yazılır */
    private List<Path> scannable(RepoInfo repo, List<Path> files, RepoResult result) {
        long maxBytes = cfg.maxFileKb * 1024L;
        List<Path> out = new ArrayList<Path>(files.size());
        for (Path f : files) {
            try {
                long size = Files.size(f);
                if (size > maxBytes) {
                    if (result != null) {
                        result.errors.add(new ScanError(repo, ScanError.Stage.SKIPPED, relative(repo.localPath, f), "Dosya çok büyük ("
                                + (size / 1024) + " KB). Sınırı scan.max.file.kb ayarıyla değiştirebilirsiniz."));
                    }
                    continue;
                }
            } catch (IOException e) {
                // okuma hatası 2. geçişte raporlanır
            }
            out.add(f);
        }
        return out;
    }

    /** "class X ... extends" içeren dosyaları ayıklamak için (generic sınırlardaki extends de geçer, zararı yok) */
    private static final Pattern CLASS_EXTENDS = Pattern.compile("\\bclass\\s+\\w+[^{;]*\\bextends\\b");

    /**
     * scan.global.hierarchy=true için reponun extends yazılan sınıflarının başlıkları. Hangi sınıfların
     * aranacağı henüz bilinmediğinden "class ... extends" içeren tüm dosyalar ayrıştırılır.
     * Okuma/ayrıştırma hataları burada değil, taramanın 2. geçişinde raporlanır.
     */
    public List<TypeHierarchy.Header> collectHeaders(RepoInfo repo) throws IOException {
        Path root = repo.localPath;
        List<Path> files = scannable(repo, collectJavaFiles(root), null);
        Map<Path, String> moduleCache = new ConcurrentHashMap<Path, String>();
        return parallel(files, f -> {
            String source = readSource(f);
            if (!source.contains("extends") || !CLASS_EXTENDS.matcher(source).find()) return null;
            List<TypeHierarchy.Header> out = new ArrayList<TypeHierarchy.Header>();
            String rel = relative(root, f).intern();
            String module = null;
            for (ClassHeader h : classHeaders(f, source)) {
                if (module == null) module = moduleOf(root, f.getParent(), moduleCache).intern();
                List<String[]> parents = new ArrayList<String[]>(h.extendsWritten.size());
                for (String ext : h.extendsWritten) {
                    List<String> c = TypeMatcher.candidates(ext, h.ctx);
                    String[] arr = new String[c.size() + 1];
                    arr[0] = ext.intern();
                    for (int i = 0; i < c.size(); i++) arr[i + 1] = c.get(i).intern();
                    parents.add(arr);
                }
                out.add(new TypeHierarchy.Header(h.fqn, rel, module, h.line, parents));
            }
            return out.isEmpty() ? null : out;
        });
    }

    public static boolean isTestPath(String rel) {
        return TEST_PATH.matcher(rel).matches();
    }

    /**
     * "extends" içeren dosyalardaki isimlerin (Java tanımlayıcıları) dizini: isim -> dosya sıraları.
     * Dosyalar paralel ve sadece bir kez okunur; dosya içerikleri bellekte tutulmaz.
     */
    private Map<String, List<Integer>> identifierIndex(List<Path> files) {
        Object[] tokens = new Object[files.size()];
        java.util.stream.IntStream.range(0, files.size()).parallel().forEach(i -> {
            try {
                String source = readSource(files.get(i));
                tokens[i] = source.contains("extends") ? identifiers(source) : null;
            } catch (IOException | RuntimeException e) {
                tokens[i] = null; // okuma hatası 2. geçişte raporlanır
            }
        });
        Map<String, List<Integer>> index = new HashMap<String, List<Integer>>();
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i] == null) continue;
            @SuppressWarnings("unchecked")
            Set<String> ids = (Set<String>) tokens[i];
            for (String id : ids) index.computeIfAbsent(id, k -> new ArrayList<Integer>(2)).add(i);
            tokens[i] = null;
        }
        return index;
    }

    /** Metindeki farklı Java tanımlayıcıları ("a.b.Foo" -> a, b, Foo) */
    private static Set<String> identifiers(String text) {
        Set<String> out = new HashSet<String>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (Character.isJavaIdentifierStart(text.charAt(i))) {
                int start = i++;
                while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) i++;
                out.add(text.substring(start, i));
            } else {
                i++;
            }
        }
        return out;
    }

    /** Bir dosyadaki sınıf başlıkları (extends yazılan sınıflar) */
    private List<ClassHeader> classHeaders(Path f, String source) {
        List<ClassHeader> out = new ArrayList<ClassHeader>();
        ParseResult<CompilationUnit> pr = parse(source);
        if (!pr.getResult().isPresent()) return out;
        CompilationUnit cu = pr.getResult().get();
        ImportContext ctx = ImportContext.of(cu);
        for (ClassOrInterfaceDeclaration cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cd.isInterface() || cd.getExtendedTypes().isEmpty()) continue;
            ClassHeader h = new ClassHeader();
            h.file = f;
            h.fqn = fqnOf(cd, cu);
            h.line = cd.getBegin().map(p -> p.line).orElse(0);
            h.ctx = ctx;
            for (ClassOrInterfaceType ext : cd.getExtendedTypes()) h.extendsWritten.add(ext.getNameWithScope());
            out.add(h);
        }
        return out;
    }

    private interface FileTask<T> {
        /** null dönerse sonuca eklenmez; liste dönerse elemanları eklenir */
        Object apply(Path f) throws IOException;
    }

    /**
     * Dosyaları paralel işler (ortak ForkJoin havuzunda; toplam işlemci kullanımı çekirdek sayısıyla
     * sınırlı kalır). Sonuçlar dosya sırasıyla döner. Okuma/ayrıştırma hatası olan dosya atlanır.
     */
    @SuppressWarnings("unchecked")
    private static <T> List<T> parallel(List<Path> files, FileTask<T> task) {
        Object[] slots = new Object[files.size()];
        java.util.stream.IntStream.range(0, files.size()).parallel().forEach(i -> {
            try {
                slots[i] = task.apply(files.get(i));
            } catch (IOException | RuntimeException e) {
                slots[i] = null;
            }
        });
        List<T> out = new ArrayList<T>();
        for (Object o : slots) {
            if (o == null) continue;
            if (o instanceof List) out.addAll((List<T>) o);
            else out.add((T) o);
        }
        return out;
    }

    /** JavaParser thread-safe değildir; her iş parçacığı kendi örneklerini kullanır. */
    private static final ThreadLocal<JavaParser> LEGACY = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)
                    .setAttributeComments(false)));
    private static final ThreadLocal<JavaParser> MODERN = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                    .setAttributeComments(false)));

    /** Önce Java 8 seviyesiyle (Java 6 kodu için en güvenlisi), olmazsa en yeni Java sürümüyle ayrıştırır. */
    private static ParseResult<CompilationUnit> parse(String source) {
        return parse(source, LEGACY.get(), MODERN.get());
    }

    private static ParseResult<CompilationUnit> parse(String source, JavaParser legacy, JavaParser modern) {
        ParseResult<CompilationUnit> pr = legacy.parse(source);
        if (!pr.isSuccessful()) {
            ParseResult<CompilationUnit> m = modern.parse(source);
            if (m.isSuccessful() || !pr.getResult().isPresent()) pr = m;
        }
        return pr;
    }

    private static boolean containsAny(String text, Iterable<String> words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    /** Tek bir dosyadaki exception oluşturma ve catch kullanımlarını bulur. */
    private void scanUnit(CompilationUnit cu, RepoInfo repo, String rel, String module, boolean test,
                          Set<String> targets, RepoResult result) {
        ImportContext ictx = ImportContext.of(cu);

        for (ObjectCreationExpr oce : cu.findAll(ObjectCreationExpr.class)) {
            String target = TypeMatcher.match(oce.getType().getNameWithScope(), ictx, targets, cfg.lenientMatch);
            if (target == null) continue;
            UsageFinding.Context ctx = context(oce);
            if (cfg.throwOnly && ctx != UsageFinding.Context.THROW) continue;

            List<ArgumentClassifier.Result> args = ArgumentClassifier.analyzeAll(oce.getArguments());
            UsagePattern matched = null;
            if (!cfg.patterns.isEmpty()) {
                for (UsagePattern p : cfg.patterns) {
                    if (p.matches(args)) {
                        matched = p;
                        break;
                    }
                }
                if (matched == null) continue; // aranan desenlerden hiçbirine uymuyor
            }

            UsageFinding u = new UsageFinding();
            fill(u, repo, rel, module, test, oce, cu);
            u.targetClass = TypeMatcher.simpleName(target);
            u.pattern = matched == null ? UsagePattern.ALL : matched.name;
            u.typeCode = matched == null ? UsagePattern.ALL_TYPE : matched.type;
            u.action = matched == null ? "" : matched.action;
            u.context = ctx;
            u.expression = BitbucketClient.abbreviate(oce.toString().replaceAll("\\s+", " "), 500);
            fillArgs(u, args);
            result.usages.add(u);
        }

        boolean targetSwallowed = cfg.collectSwallowed() && cfg.swallowedScope == Config.SwallowedScope.TARGET;
        boolean allSwallowed = cfg.collectAllSwallowed();
        if (!targetSwallowed && !allSwallowed) return;
        for (CatchClause cc : cu.findAll(CatchClause.class)) {
            List<String> matched = new ArrayList<String>();
            List<String> written = new ArrayList<String>();
            for (ClassOrInterfaceType t : caughtTypes(cc.getParameter().getType())) {
                written.add(t.getNameAsString());
                String m = TypeMatcher.match(t.getNameWithScope(), ictx, targets, cfg.lenientMatch);
                if (m != null) matched.add(TypeMatcher.simpleName(m));
            }
            if (matched.isEmpty() && !allSwallowed) continue;
            CatchFinding.SwallowKind kind = catchClassifier.classify(cc);
            if (kind == null) continue;
            CatchFinding c = new CatchFinding();
            fill(c, repo, rel, module, test, cc, cu);
            // ALL kapsamında yakalanan tiplerin hepsi, TARGET'ta sadece aranan sınıflar yazılır
            c.caughtTypes = String.join(" | ", allSwallowed ? written : matched);
            c.swallowKind = kind;
            result.swallowed.add(c);
        }
    }

    /** Argümanlardaki ilk sayıyı id, ilk mesajı da mesaj olarak bulguya yazar. */
    private static void fillArgs(UsageFinding u, List<ArgumentClassifier.Result> args) {
        for (ArgumentClassifier.Result r : args) {
            if (u.idValue.isEmpty() && r.kind == ArgKind.NUMBER) u.idValue = r.text;
            if (u.messageKind == null && r.kind.hasMessage) {
                u.messageKind = r.kind;
                u.message = r.text;
            }
        }
        if (u.messageKind == null) {
            for (ArgumentClassifier.Result r : args) {
                if (r.kind == ArgKind.EXCEPTION_MESSAGE) {
                    u.messageKind = r.kind;
                    break;
                }
            }
        }
    }

    /**
     * Ayarlardaki sınıflara, bu repoda onlardan türeyen alt sınıfları da ekler (alt sınıfın alt sınıfı dahil).
     * Yeni yapıya ait olup exception.ignore.classes'a yazılan sınıflar hariç tutulur.
     */
    /** Başlıklardan hedeflerin alt sınıflarını bulup targets'a ekler (zincirleme alt sınıflar dahil). */
    private void resolveTargets(RepoInfo repo, Path root, List<ClassHeader> headers, Set<String> targets,
                                RepoResult result, Map<Path, String> moduleCache) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ClassHeader h : headers) {
                if (targets.contains(h.fqn) || cfg.ignoreClasses.contains(h.fqn)) continue;
                for (String ext : h.extendsWritten) {
                    String parent = TypeMatcher.match(ext, h.ctx, targets, cfg.lenientMatch);
                    if (parent == null) continue;
                    targets.add(h.fqn);
                    changed = true;
                    String rel = relative(root, h.file);
                    SubclassFinding sf = new SubclassFinding();
                    sf.projectKey = repo.projectKey;
                    sf.projectName = repo.projectName;
                    sf.repo = repo.slug;
                    sf.module = moduleOf(root, h.file.getParent(), moduleCache);
                    sf.file = rel;
                    sf.testCode = TEST_PATH.matcher(rel).matches();
                    sf.className = h.fqn;
                    sf.member = "";
                    sf.line = h.line;
                    sf.link = repo.fileLink(rel, h.line);
                    sf.parent = parent;
                    result.subclasses.add(sf);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ konum bilgileri

    private static void fill(Located l, RepoInfo repo, String rel, String module, boolean test,
                             Node node, CompilationUnit cu) {
        l.projectKey = repo.projectKey;
        l.projectName = repo.projectName;
        l.repo = repo.slug;
        l.module = module;
        l.file = rel;
        l.testCode = test;
        l.className = enclosingType(node, cu);
        l.packageName = TypeMatcher.packageOf(cu);
        l.member = enclosingMember(node);
        l.memberRef = namedMemberRef(node);
        l.line = node.getBegin().map(p -> p.line).orElse(0);
        l.link = repo.fileLink(rel, l.line);
    }

    private static String fqnOf(TypeDeclaration<?> td, CompilationUnit cu) {
        Deque<String> parts = new ArrayDeque<String>();
        Node cur = td;
        while (cur != null) {
            if (cur instanceof TypeDeclaration) parts.addFirst(((TypeDeclaration<?>) cur).getNameAsString());
            cur = cur.getParentNode().orElse(null);
        }
        String pkg = TypeMatcher.packageOf(cu);
        String name = String.join(".", parts);
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /** Bulgunun içinde bulunduğu sınıf: paket.Dis.Ic veya paket.Dis.<anonim Runnable> */
    private static String enclosingType(Node node, CompilationUnit cu) {
        Deque<String> parts = new ArrayDeque<String>();
        if (node instanceof TypeDeclaration) parts.addFirst(((TypeDeclaration<?>) node).getNameAsString());
        Node child = node;
        Node cur = node.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof TypeDeclaration) {
                parts.addFirst(((TypeDeclaration<?>) cur).getNameAsString());
            } else if (cur instanceof ObjectCreationExpr && isInAnonymousBody((ObjectCreationExpr) cur, child)) {
                parts.addFirst("<anonim " + ((ObjectCreationExpr) cur).getType().getNameAsString() + ">");
            }
            child = cur;
            cur = cur.getParentNode().orElse(null);
        }
        String pkg = TypeMatcher.packageOf(cu);
        String name = String.join(".", parts);
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    private static boolean isInAnonymousBody(ObjectCreationExpr oce, Node child) {
        if (!oce.getAnonymousClassBody().isPresent()) return false;
        for (BodyDeclaration<?> b : oce.getAnonymousClassBody().get()) {
            if (b == child) return true;
        }
        return false;
    }

    /** Bulgunun içinde bulunduğu metot, constructor, blok veya alan. */
    private static String enclosingMember(Node node) {
        Node cur = node.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof MethodDeclaration) {
                return ((MethodDeclaration) cur).getSignature().asString();
            }
            if (cur instanceof ConstructorDeclaration) {
                return ((ConstructorDeclaration) cur).getSignature().asString() + " [constructor]";
            }
            if (cur instanceof InitializerDeclaration) {
                return ((InitializerDeclaration) cur).isStatic() ? "<static blok>" : "<instance blok>";
            }
            if (cur instanceof FieldDeclaration) {
                List<String> names = new ArrayList<String>();
                for (VariableDeclarator v : ((FieldDeclaration) cur).getVariables()) names.add(v.getNameAsString());
                return "<alan: " + String.join(", ", names) + ">";
            }
            if (cur instanceof TypeDeclaration) break;
            cur = cur.getParentNode().orElse(null);
        }
        return "";
    }

    /**
     * Veritabanı eşleştirmesi için üye bilgisi. Yukarı doğru ilk adlandırılmış tipe (TypeDeclaration)
     * kadar çıkılır ve yolda görülen en dıştaki üye alınır. Normal kodda bu, bulgunun doğrudan içinde
     * olduğu metottur; anonim sınıf içindeyse (anonim sınıflar TypeDeclaration değildir) onu içeren
     * dış metottur.
     */
    private static MemberRef namedMemberRef(Node node) {
        MemberRef found = MemberRef.NONE;
        Node cur = node.getParentNode().orElse(null);
        while (cur != null && !(cur instanceof TypeDeclaration)) {
            if (cur instanceof MethodDeclaration) {
                MethodDeclaration md = (MethodDeclaration) cur;
                found = new MemberRef(MemberRef.Kind.METHOD, md.getNameAsString(), paramTypes(md.getParameters()));
            } else if (cur instanceof ConstructorDeclaration) {
                ConstructorDeclaration cd = (ConstructorDeclaration) cur;
                found = new MemberRef(MemberRef.Kind.CONSTRUCTOR, cd.getNameAsString(), paramTypes(cd.getParameters()));
            } else if (cur instanceof InitializerDeclaration) {
                boolean st = ((InitializerDeclaration) cur).isStatic();
                found = new MemberRef(st ? MemberRef.Kind.STATIC_INIT : MemberRef.Kind.INSTANCE_INIT, "",
                        Collections.<String>emptyList());
            } else if (cur instanceof FieldDeclaration) {
                List<String> names = new ArrayList<String>();
                for (VariableDeclarator v : ((FieldDeclaration) cur).getVariables()) names.add(v.getNameAsString());
                found = new MemberRef(MemberRef.Kind.FIELD, String.join(",", names), Collections.<String>emptyList());
            }
            cur = cur.getParentNode().orElse(null);
        }
        return found;
    }

    private static List<String> paramTypes(NodeList<Parameter> params) {
        List<String> out = new ArrayList<String>();
        for (Parameter p : params) out.add(p.getType().asString() + (p.isVarArgs() ? "[]" : ""));
        return out;
    }

    /** Exception'ın oluşturulduğu bağlam: doğrudan throw mu, bir değişkene mi atanıyor vb. */
    private static UsageFinding.Context context(ObjectCreationExpr oce) {
        Node p = oce.getParentNode().orElse(null);
        while (p instanceof EnclosedExpr || p instanceof CastExpr) p = p.getParentNode().orElse(null);
        if (p instanceof ThrowStmt) return UsageFinding.Context.THROW;
        if (p instanceof ReturnStmt) return UsageFinding.Context.RETURN;
        if (p instanceof VariableDeclarator || p instanceof AssignExpr) return UsageFinding.Context.ASSIGNMENT;
        if (p instanceof MethodCallExpr || p instanceof ObjectCreationExpr) return UsageFinding.Context.ARGUMENT;
        return UsageFinding.Context.OTHER;
    }

    private static List<ClassOrInterfaceType> caughtTypes(Type t) {
        List<ClassOrInterfaceType> out = new ArrayList<ClassOrInterfaceType>();
        if (t instanceof UnionType) {
            for (ReferenceType rt : ((UnionType) t).getElements()) {
                if (rt.isClassOrInterfaceType()) out.add(rt.asClassOrInterfaceType());
            }
        } else if (t.isClassOrInterfaceType()) {
            out.add(t.asClassOrInterfaceType());
        }
        return out;
    }

    // ------------------------------------------------------------------ dosya işlemleri

    private List<Path> collectJavaFiles(final Path root) throws IOException {
        final List<Path> out = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    String n = dir.getFileName().toString();
                    if (n.startsWith(".") || cfg.excludeDirs.contains(n)) return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String n = file.getFileName().toString();
                if (n.endsWith(".java")) out.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(out);
        return out;
    }

    /** Önce UTF-8 dener; geçersiz bayt varsa eski projelerde yaygın olan yedek karakter setiyle okur. */
    private String readSource(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int offset = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            offset = 3; // UTF-8 BOM
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, cfg.fallbackCharset);
        }
    }

    private static String moduleOf(Path root, Path dir, Map<Path, String> cache) {
        return ModuleResolver.moduleOf(root, dir, cache);
    }

    private static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
