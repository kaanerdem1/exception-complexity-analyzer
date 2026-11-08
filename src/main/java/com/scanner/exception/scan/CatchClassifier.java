package com.scanner.exception.scan;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.scanner.exception.model.CatchFinding.SwallowKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Bir catch bloğunun hatayı yutup yutmadığını gövdesine bakarak sınıflandırır:
 * EMPTY (boş veya sadece yorum), LOG_ONLY (sadece log), LOG_AND_RETURN (log ve ardından return),
 * RETURN_ONLY (log olmadan sadece return). İki return türünde de dönen değer hatayı taşıyorsa
 * (bkz. {@link #passesError}) yutma sayılmaz.
 *
 * Log çağrısı: e.printStackTrace(), System.out/err.print*, adı scan.swallowed.log.names'te olan
 * (ör. log, logger) veya "logger" ile biten nesnelerin metotları, getLogger()/getLog() sonucunun
 * metotları ve logError(...) gibi "log" ile başlayan nesnesiz çağrılar. "if (log.isDebugEnabled())
 * log.debug(...)" gibi sadece log içeren if blokları da log sayılır.
 */
public final class CatchClassifier {

    /** logError, log_error, log(...); login, logout gibi adlar hariç */
    private static final Pattern LOG_METHOD = Pattern.compile("log([A-Z_].*)?");

    private final Set<String> logNames = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);

    public CatchClassifier(List<String> logNames) {
        this.logNames.addAll(logNames);
    }

    /** Hatayı yutmuyorsa null */
    public SwallowKind classify(CatchClause cc) {
        List<Statement> stmts = new ArrayList<Statement>();
        for (Statement s : cc.getBody().getStatements()) if (!(s instanceof EmptyStmt)) stmts.add(s);
        if (stmts.isEmpty()) return SwallowKind.EMPTY;
        Statement last = stmts.get(stmts.size() - 1);
        if (last instanceof ReturnStmt) {
            if (passesError((ReturnStmt) last, cc)) return null;
            List<Statement> before = stmts.subList(0, stmts.size() - 1);
            if (before.isEmpty()) return SwallowKind.RETURN_ONLY;
            return allLog(before) ? SwallowKind.LOG_AND_RETURN : null;
        }
        return allLog(stmts) ? SwallowKind.LOG_ONLY : null;
    }

    /**
     * return ifadesi hatayı çağırana taşıyorsa true (yutma sayılmaz):
     * yakalanan değişken (adı ne olursa olsun: e, ex, t) ifadede geçiyor, ör. return handle(ex),
     * return new HataCevabi(ex.getMessage()); ifadede adı Exception / Error / Throwable ile biten bir
     * tip oluşturuluyor, ör. return new BusinessException("x"); veya catch'in bulunduğu metodun dönüş
     * tipi böyle bir tip. Değersiz return ve null, false, "", EMPTY, new ArrayList() gibi değerler hatayı taşımaz.
     */
    static boolean passesError(ReturnStmt r, CatchClause cc) {
        if (!r.getExpression().isPresent()) return false;
        Expression e = r.getExpression().get();
        String var = cc.getParameter().getNameAsString();
        for (NameExpr n : e.findAll(NameExpr.class)) if (n.getNameAsString().equals(var)) return true;
        for (ObjectCreationExpr o : e.findAll(ObjectCreationExpr.class)) {
            if (isErrorType(o.getType().getNameAsString())) return true;
        }
        // En yakın metot (lambda içindeki return lambda'nın sonucudur, metodun değil)
        for (Node n = cc.getParentNode().orElse(null); n != null; n = n.getParentNode().orElse(null)) {
            if (n instanceof LambdaExpr) return false;
            if (n instanceof MethodDeclaration) {
                Type t = ((MethodDeclaration) n).getType();
                return t.isClassOrInterfaceType() && isErrorType(t.asClassOrInterfaceType().getNameAsString());
            }
        }
        return false;
    }

    private static boolean isErrorType(String simpleName) {
        return simpleName.endsWith("Exception") || simpleName.endsWith("Error") || simpleName.endsWith("Throwable");
    }

    private boolean allLog(List<Statement> stmts) {
        for (Statement s : stmts) if (!isLog(s)) return false;
        return true;
    }

    private boolean isLog(Statement s) {
        if (s instanceof EmptyStmt) return true;
        if (s instanceof BlockStmt) {
            List<Statement> inner = new ArrayList<Statement>();
            for (Statement x : ((BlockStmt) s).getStatements()) if (!(x instanceof EmptyStmt)) inner.add(x);
            return !inner.isEmpty() && allLog(inner);
        }
        if (s instanceof IfStmt) {
            IfStmt i = (IfStmt) s;
            return isLog(i.getThenStmt()) && (!i.getElseStmt().isPresent() || isLog(i.getElseStmt().get()));
        }
        if (s instanceof ExpressionStmt) {
            Expression e = ((ExpressionStmt) s).getExpression();
            return e instanceof MethodCallExpr && isLogCall((MethodCallExpr) e);
        }
        return false;
    }

    boolean isLogCall(MethodCallExpr m) {
        String name = m.getNameAsString();
        if (name.equals("printStackTrace")) return true;
        if (!m.getScope().isPresent()) return logNames.contains(name) || LOG_METHOD.matcher(name).matches();
        Expression scope = m.getScope().get();
        String text = scope.toString();
        if (text.equals("System.out") || text.equals("System.err")) return true;
        if (scope instanceof MethodCallExpr) {
            String n = ((MethodCallExpr) scope).getNameAsString();
            return n.equals("getLogger") || n.equals("getLog");
        }
        String target = scope instanceof NameExpr ? ((NameExpr) scope).getNameAsString()
                : scope instanceof FieldAccessExpr ? ((FieldAccessExpr) scope).getNameAsString() : null;
        return target != null && (logNames.contains(target) || target.toLowerCase(Locale.ROOT).endsWith("logger"));
    }
}
