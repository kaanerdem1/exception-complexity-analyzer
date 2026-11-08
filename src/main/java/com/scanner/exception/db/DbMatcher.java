package com.scanner.exception.db;

import com.scanner.exception.model.Located;
import com.scanner.exception.model.MemberRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tarama bulgularını veritabanındaki sınıf ve metot kayıtlarıyla eşleştirir.
 *
 * Sınıf: className (paket + iç içe sınıf yolu) FQCN'e çevrilir. İç içe sınıflar için hem
 *        "a.b.Dis$Ic" hem "a.b.Dis.Ic" denenir; bulunamazsa dıştaki sınıfa doğru kısaltılır.
 *        Anonim sınıf içindeki kullanımlar onu içeren adlandırılmış sınıfa eşlenir.
 * Metot: tablodaki ad "(" karakterinden kesilerek karşılaştırılır; böylece "getRate" ile
 *        "getRate(String a, String b)" ve yarım kalmış "getRate(String a," aynı metot sayılır.
 *        Aynı adda birden fazla metot (overload) varsa parametre tipleriyle, o da yetmezse
 *        parametre sayısıyla ayırt edilir.
 */
public final class DbMatcher {

    static final String MATCHED = "MATCHED";
    static final String METHOD_AMBIGUOUS = "METHOD_AMBIGUOUS";
    static final String METHOD_NOT_FOUND = "METHOD_NOT_FOUND";
    static final String NO_METHOD = "NO_METHOD";
    static final String CLASS_AMBIGUOUS = "CLASS_AMBIGUOUS";
    static final String CLASS_NOT_FOUND = "CLASS_NOT_FOUND";

    private static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(\\([^)]*\\))?");
    private static final Pattern DESCRIPTOR = Pattern.compile("\\(((\\[)*([ZBCSIJFD]|L[^;()]+;))*\\).*");

    private final boolean caseInsensitive;
    private final List<String> constructorNames;

    /**
     * @param caseInsensitive  FQCN karşılaştırması büyük/küçük harf duyarsız mı
     * @param constructorNames tabloda constructor'ın hangi adlarla tutulduğu; "{class}" sınıfın basit adıdır
     */
    public DbMatcher(boolean caseInsensitive, List<String> constructorNames) {
        this.caseInsensitive = caseInsensitive;
        this.constructorNames = constructorNames;
    }

    // =====================================================================
    //  Toplu eşleştirme
    // =====================================================================

    /** Bulguların hepsini iki sorguda (sınıflar, metotlar) eşleştirir ve sonucu bulguların üzerine yazar. */
    public void resolveAll(List<? extends Located> findings, DbCatalog db) throws Exception {
        Set<String> wanted = new LinkedHashSet<String>();
        for (Located l : findings) {
            for (ClassCandidate c : classCandidates(l)) wanted.add(key(c.fqcn));
        }
        Map<String, List<DbCatalog.ClassRow>> classes = db.findClasses(wanted);

        Set<Long> classIds = new LinkedHashSet<Long>();
        for (List<DbCatalog.ClassRow> rows : classes.values()) {
            for (DbCatalog.ClassRow r : rows) classIds.add(Long.valueOf(r.id));
        }
        Map<Long, List<DbCatalog.MethodRow>> methods = classIds.isEmpty()
                ? Collections.<Long, List<DbCatalog.MethodRow>>emptyMap() : db.findMethods(classIds);

        for (Located l : findings) resolve(l, classes, methods);
    }

    void resolve(Located l, Map<String, List<DbCatalog.ClassRow>> classes,
                 Map<Long, List<DbCatalog.MethodRow>> methods) {
        l.dbClassId = null;
        l.dbMethodId = null;
        List<String> notes = new ArrayList<String>();

        ClassCandidate matchedCandidate = null;
        List<DbCatalog.ClassRow> rows = null;
        for (ClassCandidate c : classCandidates(l)) {
            List<DbCatalog.ClassRow> r = classes.get(key(c.fqcn));
            if (r != null && !r.isEmpty()) {
                matchedCandidate = c;
                rows = r;
                break;
            }
        }
        if (matchedCandidate == null) {
            l.matchStatus = CLASS_NOT_FOUND;
            l.matchNote = "Aranan: " + joinFqcns(classCandidates(l));
            return;
        }
        if (matchedCandidate.note != null) notes.add(matchedCandidate.note);

        // Aynı FQCN birden fazla kayıtta olabilir; metodu bulunan kayıt tercih edilir
        MethodResult best = null;
        DbCatalog.ClassRow bestRow = null;
        int rowsWithMethod = 0;
        for (DbCatalog.ClassRow row : sortById(rows)) {
            List<DbCatalog.MethodRow> ms = methods.get(Long.valueOf(row.id));
            MethodResult mr = matchMethod(l.memberRef, matchedCandidate.simpleName,
                    ms == null ? Collections.<DbCatalog.MethodRow>emptyList() : ms);
            if (mr.method != null) rowsWithMethod++;
            if (best == null || rank(mr.status) < rank(best.status)) {
                best = mr;
                bestRow = row;
            }
        }
        l.dbClassId = Long.valueOf(bestRow.id);
        l.dbMethodId = best.method == null ? null : Long.valueOf(best.method.id);
        if (best.note != null) notes.add(best.note);

        if (rows.size() > 1 && rowsWithMethod != 1) {
            l.matchStatus = CLASS_AMBIGUOUS;
            notes.add(0, rows.size() + " sınıf kaydı var, en küçük id seçildi");
        } else {
            l.matchStatus = best.status;
            if (rows.size() > 1) notes.add(0, rows.size() + " sınıf kaydından metodu içeren seçildi");
        }
        l.matchNote = String.join("; ", notes);
    }

    private static int rank(String status) {
        if (MATCHED.equals(status)) return 0;
        if (METHOD_AMBIGUOUS.equals(status)) return 1;
        if (NO_METHOD.equals(status)) return 2;
        return 3;
    }

    // =====================================================================
    //  Sınıf adayları
    // =====================================================================

    static final class ClassCandidate {
        final String fqcn;
        /** Constructor eşleştirmesi için sınıfın basit adı */
        final String simpleName;
        /** Tam sınıf yerine dıştaki sınıfa düşüldüyse açıklama */
        final String note;

        ClassCandidate(String fqcn, String simpleName, String note) {
            this.fqcn = fqcn;
            this.simpleName = simpleName;
            this.note = note;
        }
    }

    /** "a.b.Dis.Ic.<anonim Runnable>" -> a.b.Dis$Ic, a.b.Dis.Ic, a.b.Dis (sırayla) */
    static List<ClassCandidate> classCandidates(Located l) {
        String pkg = l.packageName == null ? "" : l.packageName;
        String path = l.className;
        if (!pkg.isEmpty() && path.startsWith(pkg + ".")) path = path.substring(pkg.length() + 1);

        List<String> named = new ArrayList<String>();
        boolean anonymous = false;
        for (String part : path.split("\\.")) {
            if (part.startsWith("<")) {
                anonymous = true;
                break;
            }
            named.add(part);
        }
        List<ClassCandidate> out = new ArrayList<ClassCandidate>();
        if (named.isEmpty()) return out;
        String prefix = pkg.isEmpty() ? "" : pkg + ".";
        for (int k = named.size(); k >= 1; k--) {
            List<String> sub = named.subList(0, k);
            String simple = sub.get(k - 1);
            String note = null;
            if (anonymous) note = "Anonim sınıf içinde; " + simple + " sınıfına eşlendi";
            if (k < named.size()) note = "İç sınıf bulunamadı; dıştaki " + simple + " sınıfına eşlendi";
            out.add(new ClassCandidate(prefix + String.join("$", sub), simple, note));
            if (k > 1) out.add(new ClassCandidate(prefix + String.join(".", sub), simple, note));
        }
        return out;
    }

    private static String joinFqcns(List<ClassCandidate> cs) {
        List<String> s = new ArrayList<String>();
        for (ClassCandidate c : cs) s.add(c.fqcn);
        return String.join(", ", s);
    }

    String key(String fqcn) {
        return caseInsensitive ? fqcn.toLowerCase(Locale.ENGLISH) : fqcn;
    }

    // =====================================================================
    //  Metot eşleştirme
    // =====================================================================

    static final class MethodResult {
        final String status;
        final DbCatalog.MethodRow method;
        final String note;

        MethodResult(String status, DbCatalog.MethodRow method, String note) {
            this.status = status;
            this.method = method;
            this.note = note;
        }
    }

    MethodResult matchMethod(MemberRef ref, String classSimpleName, List<DbCatalog.MethodRow> methods) {
        Set<String> names = new LinkedHashSet<String>();
        switch (ref.kind) {
            case METHOD:
                names.add(ref.name);
                break;
            case CONSTRUCTOR:
            case INSTANCE_INIT:
                for (String n : constructorNames) names.add(n.replace("{class}", classSimpleName));
                break;
            case STATIC_INIT:
                names.add("<clinit>");
                break;
            default:
                return new MethodResult(NO_METHOD, null, ref.kind == MemberRef.Kind.FIELD
                        ? "Alan tanımında (" + ref.name + "), metot yok" : "Metot dışında");
        }

        List<DbCatalog.MethodRow> byName = new ArrayList<DbCatalog.MethodRow>();
        for (DbCatalog.MethodRow m : methods) {
            if (names.contains(baseName(m.name))) byName.add(m);
        }
        byName = sortMethods(byName);
        String wanted = (ref.kind == MemberRef.Kind.METHOD ? ref.name : String.join("|", names));
        if (byName.isEmpty()) {
            return new MethodResult(METHOD_NOT_FOUND, null, "Metot bulunamadı: " + wanted);
        }
        if (byName.size() == 1) return new MethodResult(MATCHED, byName.get(0), null);

        // Overload: parametre tipleriyle ayırt et (bloklar ve bilinmeyen parametreler hariç)
        boolean ownParamsKnown = ref.kind == MemberRef.Kind.METHOD || ref.kind == MemberRef.Kind.CONSTRUCTOR;
        if (ownParamsKnown) {
            List<String> own = normalizeAll(ref.paramTypes);
            List<DbCatalog.MethodRow> exact = new ArrayList<DbCatalog.MethodRow>();
            List<DbCatalog.MethodRow> sameCount = new ArrayList<DbCatalog.MethodRow>();
            int withParams = 0;
            for (DbCatalog.MethodRow m : byName) {
                DbParams p = dbParams(m);
                if (p == null) continue;
                withParams++;
                if (p.matches(own)) exact.add(m);
                if (!p.truncated && p.types.size() == own.size()) sameCount.add(m);
            }
            if (exact.size() == 1) {
                return new MethodResult(MATCHED, exact.get(0), byName.size() + " overload içinden parametre tipleriyle seçildi");
            }
            if (exact.isEmpty() && sameCount.size() == 1) {
                return new MethodResult(MATCHED, sameCount.get(0), byName.size() + " overload içinden parametre sayısıyla seçildi");
            }
            if (exact.size() > 1) {
                return new MethodResult(METHOD_AMBIGUOUS, exact.get(0), exact.size() + " kayıt aynı imzada, en küçük id seçildi");
            }
            if (withParams == 0) {
                return new MethodResult(METHOD_AMBIGUOUS, byName.get(0), byName.size()
                        + " overload var, tabloda parametre bilgisi olmadığı için en küçük id seçildi");
            }
        }
        return new MethodResult(METHOD_AMBIGUOUS, byName.get(0), byName.size()
                + " aday ayırt edilemedi, en küçük id seçildi");
    }

    /** "public static String getRate(String a," -> getRate */
    static String baseName(String dbName) {
        if (dbName == null) return "";
        String s = dbName;
        int p = s.indexOf('(');
        if (p >= 0) s = s.substring(0, p);
        s = s.trim();
        int sp = Math.max(s.lastIndexOf(' '), s.lastIndexOf('\t'));
        if (sp >= 0) s = s.substring(sp + 1); // niteleyici ve dönüş tipi: "public static CSBag listele"
        // Boşluksuz generic önek "<T>foo" temizlenir; <init> ve <clinit> gibi özel adlar korunur
        if (!s.matches("<\\w+>") && s.lastIndexOf('>') >= 0) s = s.substring(s.lastIndexOf('>') + 1);
        // "Sinif.metot" veya "Sinif#metot" şeklinde tutulmuşsa
        int dot = Math.max(s.lastIndexOf('.'), s.lastIndexOf('#'));
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        return s.trim();
    }

    // =====================================================================
    //  Parametre ayrıştırma
    // =====================================================================

    static final class DbParams {
        final List<String> types;
        /** Metin kapanış parantezi olmadan kesilmişse sadece ilk parametreler bilinir */
        final boolean truncated;

        DbParams(List<String> types, boolean truncated) {
            this.types = types;
            this.truncated = truncated;
        }

        boolean matches(List<String> own) {
            if (!truncated) return types.equals(own);
            return own.size() >= types.size() && own.subList(0, types.size()).equals(types);
        }
    }

    /** Parametreleri önce imza sütunundan, yoksa addaki parantezden okur; bilgi yoksa null */
    static DbParams dbParams(DbCatalog.MethodRow m) {
        DbParams p = parseParams(m.signature);
        return p != null ? p : parseParams(m.name);
    }

    static DbParams parseParams(String text) {
        if (text == null) return null;
        String t = text.trim();
        int open = t.indexOf('(');
        if (open < 0) return null;
        String rest = t.substring(open);
        if (DESCRIPTOR.matcher(rest).matches() && !rest.contains(" ") && !rest.contains(",")) {
            return new DbParams(parseDescriptor(rest), false);
        }
        int close = rest.lastIndexOf(')');
        boolean truncated = close < 0;
        String inner = truncated ? rest.substring(1) : rest.substring(1, close);

        List<String> parts = splitTopLevel(inner);
        if (truncated && !inner.trim().endsWith(",") && !parts.isEmpty()) {
            parts.remove(parts.size() - 1); // son parametre yarım kalmış olabilir
        }
        List<String> types = new ArrayList<String>();
        for (String part : parts) {
            String type = typeOfDeclaration(part);
            if (!type.isEmpty()) types.add(normalize(type));
        }
        return new DbParams(types, truncated);
    }

    /** "final @NotNull List<String> ids" -> "List<String>"; "String a[]" -> "String[]"; "String" -> "String" */
    private static String typeOfDeclaration(String decl) {
        String d = ANNOTATION.matcher(decl).replaceAll(" ").replaceAll("\\bfinal\\b", " ").trim();
        if (d.isEmpty()) return "";
        d = collapseGenerics(d);
        String[] tokens = d.split("\\s+");
        if (tokens.length == 1) return tokens[0];
        String name = tokens[tokens.length - 1];
        String type = String.join(" ", Arrays.copyOf(tokens, tokens.length - 1));
        if (name.endsWith("[]")) type = type + name.substring(name.indexOf('['));
        return type;
    }

    /** "Map<String, List<Long>>" içindeki boşlukları kaldırır ki tip tek parça kalsın */
    private static String collapseGenerics(String s) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (depth > 0 && Character.isWhitespace(c)) continue;
            sb.append(c);
        }
        return sb.toString().replaceAll("\\s+(\\[\\]|\\.\\.\\.)", "$1");
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static final Map<Character, String> PRIMITIVES = new HashMap<Character, String>();
    static {
        PRIMITIVES.put('Z', "boolean"); PRIMITIVES.put('B', "byte"); PRIMITIVES.put('C', "char");
        PRIMITIVES.put('S', "short"); PRIMITIVES.put('I', "int"); PRIMITIVES.put('J', "long");
        PRIMITIVES.put('F', "float"); PRIMITIVES.put('D', "double");
    }

    /** "(Ljava/lang/String;[II)V" -> [String, int[], int] */
    private static List<String> parseDescriptor(String d) {
        List<String> out = new ArrayList<String>();
        int i = 1;
        while (i < d.length() && d.charAt(i) != ')') {
            int dims = 0;
            while (d.charAt(i) == '[') {
                dims++;
                i++;
            }
            String type;
            if (d.charAt(i) == 'L') {
                int end = d.indexOf(';', i);
                type = d.substring(i + 1, end).replace('/', '.').replace('$', '.');
                i = end + 1;
            } else {
                type = PRIMITIVES.get(Character.valueOf(d.charAt(i)));
                i++;
            }
            StringBuilder sb = new StringBuilder(normalize(type));
            for (int k = 0; k < dims; k++) sb.append("[]");
            out.add(sb.toString());
        }
        return out;
    }

    static List<String> normalizeAll(List<String> types) {
        List<String> out = new ArrayList<String>();
        for (String t : types) out.add(normalize(t));
        return out;
    }

    /** "java.util.List<String>" -> List; "String..." -> String[]; "Map.Entry<K,V>[]" -> Entry[] */
    static String normalize(String type) {
        String t = ANNOTATION.matcher(type).replaceAll("").replaceAll("\\s+", "");
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : t.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0) sb.append(c);
        }
        t = sb.toString().replace("...", "[]");
        Matcher m = Pattern.compile("^(.*?)((\\[\\])*)$").matcher(t);
        String base = t;
        String dims = "";
        if (m.matches()) {
            base = m.group(1);
            dims = m.group(2);
        }
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(dot + 1);
        return base + dims;
    }

    private static List<DbCatalog.ClassRow> sortById(List<DbCatalog.ClassRow> rows) {
        List<DbCatalog.ClassRow> s = new ArrayList<DbCatalog.ClassRow>(rows);
        Collections.sort(s, (a, b) -> Long.compare(a.id, b.id));
        return s;
    }

    private static List<DbCatalog.MethodRow> sortMethods(List<DbCatalog.MethodRow> rows) {
        List<DbCatalog.MethodRow> s = new ArrayList<DbCatalog.MethodRow>(rows);
        Collections.sort(s, (a, b) -> Long.compare(a.id, b.id));
        return s;
    }
}
