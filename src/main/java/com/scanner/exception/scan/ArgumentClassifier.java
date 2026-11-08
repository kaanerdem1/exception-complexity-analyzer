package com.scanner.exception.scan;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.scanner.exception.repo.BitbucketClient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Constructor argümanlarının her birinin türünü ve varsa değerini (sayı, mesaj şablonu) çıkarır. */
public final class ArgumentClassifier {

    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]+");

    private ArgumentClassifier() { }

    /** Constructor'a geçilen tek bir argümanın türü. */
    public enum ArgKind {
        STRING_LITERAL("String sabit", true),
        STRING_CONCAT("String birleştirme", true),
        STRING_FORMAT("String.format / MessageFormat", true),
        EXCEPTION_MESSAGE("Başka exception'ın mesajı (getMessage)", false),
        CAUSE("Yakalanan exception (cause)", false),
        NUMBER("Sayı", false),
        CONSTANT_REF("Sabit referansı", false),
        VARIABLE("Değişken / ifade", false);

        public final String label;
        /** Mesaj metni kodun içinde yazılı mı (sabit, birleştirme veya format) */
        public final boolean hasMessage;

        ArgKind(String label, boolean hasMessage) {
            this.label = label;
            this.hasMessage = hasMessage;
        }
    }

    static final class Result {
        final ArgKind kind;
        /** NUMBER için sayının kendisi, mesajlar için şablon, diğerleri için ifadenin kısaltılmış hali */
        final String text;
        /** Argümanın boşluksuz kaynak kodu, ör. GENERALERRORCODE.ERROR_CODE */
        String source = "";
        /** Argüman bir metot çağrısıysa metodun adı, ör. getErrorCode */
        String callName;

        Result(ArgKind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        /** NUMBER türündeki argümanın değeri; 0, 0x0, 0L gibi yazımlar aynı değere çözülür. */
        Long numericValue() {
            if (kind != ArgKind.NUMBER) return null;
            String s = text.replace("_", "").replaceAll("[lL]$", "");
            try {
                return Long.decode(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    static List<Result> analyzeAll(NodeList<Expression> args) {
        List<Result> out = new ArrayList<Result>();
        for (Expression e : args) out.add(analyze(e));
        return out;
    }

    static Result analyze(Expression expr) {
        Expression a = unwrap(expr);
        Result r = classify(a);
        r.source = a.toString().replaceAll("\\s+", "");
        r.callName = a.isMethodCallExpr() ? a.asMethodCallExpr().getNameAsString() : null;
        return r;
    }

    private static Result classify(Expression a) {

        if (a.isStringLiteralExpr()) {
            return new Result(ArgKind.STRING_LITERAL, a.asStringLiteralExpr().asString());
        }
        if (a.isIntegerLiteralExpr() || a.isLongLiteralExpr()) {
            return new Result(ArgKind.NUMBER, a.toString());
        }
        if (a.isUnaryExpr() && a.asUnaryExpr().getOperator() == UnaryExpr.Operator.MINUS) {
            Expression inner = unwrap(a.asUnaryExpr().getExpression());
            if (inner.isIntegerLiteralExpr() || inner.isLongLiteralExpr()) {
                return new Result(ArgKind.NUMBER, "-" + inner.toString());
            }
        }
        if (a.isBinaryExpr() && a.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            List<Expression> parts = new ArrayList<Expression>();
            flatten(a, parts);
            for (Expression p : parts) {
                if (p.isStringLiteralExpr()) return new Result(ArgKind.STRING_CONCAT, template(parts));
            }
            return new Result(ArgKind.VARIABLE, shortText(a));
        }
        if (a.isMethodCallExpr()) {
            MethodCallExpr m = a.asMethodCallExpr();
            String name = m.getNameAsString();
            String scope = m.getScope().map(Node::toString).orElse("");
            boolean formatter = scope.equals("String") || scope.equals("java.lang.String")
                    || scope.equals("MessageFormat") || scope.endsWith(".MessageFormat");
            if ("format".equals(name) && formatter && !m.getArguments().isEmpty()) {
                Expression f = unwrap(m.getArguments().get(0));
                if (f.isStringLiteralExpr()) {
                    return new Result(ArgKind.STRING_FORMAT, f.asStringLiteralExpr().asString());
                }
            }
            if ((name.equals("getMessage") || name.equals("getLocalizedMessage")) && m.getArguments().isEmpty()) {
                return new Result(ArgKind.EXCEPTION_MESSAGE, shortText(a));
            }
            return new Result(ArgKind.VARIABLE, shortText(a));
        }
        if (a.isNameExpr()) {
            String n = a.asNameExpr().getNameAsString();
            if (isCatchParameter(a, n)) return new Result(ArgKind.CAUSE, n);
            if (CONSTANT.matcher(n).matches()) return new Result(ArgKind.CONSTANT_REF, n);
            return new Result(ArgKind.VARIABLE, n);
        }
        if (a.isFieldAccessExpr() && CONSTANT.matcher(a.asFieldAccessExpr().getNameAsString()).matches()) {
            return new Result(ArgKind.CONSTANT_REF, a.toString());
        }
        return new Result(ArgKind.VARIABLE, shortText(a));
    }

    /** Parantez ve cast'leri soyar: ((String) "x") -> "x" */
    static Expression unwrap(Expression e) {
        Expression cur = e;
        while (true) {
            if (cur.isEnclosedExpr()) cur = cur.asEnclosedExpr().getInner();
            else if (cur.isCastExpr()) cur = cur.asCastExpr().getExpression();
            else return cur;
        }
    }

    private static void flatten(Expression e, List<Expression> out) {
        Expression u = unwrap(e);
        if (u.isBinaryExpr() && u.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            flatten(u.asBinaryExpr().getLeft(), out);
            flatten(u.asBinaryExpr().getRight(), out);
        } else {
            out.add(u);
        }
    }

    /** "Kayıt bulunamadı: " + id + " (" + tip + ")"  ->  "Kayıt bulunamadı: {0} ({1})" */
    private static String template(List<Expression> parts) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (Expression p : parts) {
            if (p.isStringLiteralExpr()) sb.append(p.asStringLiteralExpr().asString());
            else if (p.isCharLiteralExpr()) sb.append(p.asCharLiteralExpr().asChar());
            else sb.append('{').append(idx++).append('}');
        }
        return sb.toString();
    }

    private static String shortText(Expression e) {
        return BitbucketClient.abbreviate(e.toString().replaceAll("\\s+", " "), 200);
    }

    private static boolean isCatchParameter(Node node, String name) {
        Node cur = node;
        while (cur.getParentNode().isPresent()) {
            cur = cur.getParentNode().get();
            if (cur instanceof CatchClause
                    && ((CatchClause) cur).getParameter().getNameAsString().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
