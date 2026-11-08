package com.scanner.complexity;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Halstead + Java 6 code/exception smells + field access (legacy Downloads katmanı). */
final class LegacyAstMetricsVisitor extends VoidVisitorAdapter<Void> {

    private static final Set<String> GENERIC_CAPABLE_TYPES = Set.of(
            "List", "ArrayList", "LinkedList", "Map", "HashMap", "TreeMap", "LinkedHashMap",
            "Set", "HashSet", "TreeSet", "LinkedHashSet", "Collection", "Queue", "Deque",
            "ArrayDeque", "Vector", "Stack", "Hashtable", "Iterator", "Comparator", "Comparable",
            "Enumeration", "Callable", "Future");

    private static final Pattern IP_PATTERN =
            Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");
    private static final Pattern SQL_PATTERN = Pattern.compile(
            "(?i)\\b(select\\s+.{0,300}?\\s+from\\s|insert\\s+into\\s|update\\s+\\S+\\s+set\\s|"
                    + "delete\\s+from\\s|merge\\s+into\\s|create\\s+table\\s|drop\\s+table\\s)");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)^(jdbc:|https?://|ftp://|ldap://)");

    private final Set<String> fieldNames;
    private final Map<String, Integer> classOperators = new LinkedHashMap<>();
    private final Map<String, Integer> classOperands = new LinkedHashMap<>();
    private final Map<String, SupplementalMetrics.LegacyMethodMetrics> methods = new LinkedHashMap<>();

    private String typeQualifiedName;
    private MethodFrame methodFrame;

    private LegacyAstMetricsVisitor(Set<String> fieldNames) {
        this.fieldNames = fieldNames;
    }

    static SupplementalMetrics.LegacyTypeMetrics analyzeType(TypeDeclaration<?> declaration) {
        Set<String> fields = extractFieldNames(declaration);
        LegacyAstMetricsVisitor visitor = new LegacyAstMetricsVisitor(fields);
        visitor.typeQualifiedName = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
        declaration.accept(visitor, null);
        return new SupplementalMetrics.LegacyTypeMetrics(fields,
                HalsteadMetrics.from(visitor.classOperators, visitor.classOperands), Map.copyOf(visitor.methods));
    }

    private static Set<String> extractFieldNames(TypeDeclaration<?> declaration) {
        Set<String> names = new HashSet<>();
        for (FieldDeclaration fd : declaration.getFields()) {
            if (fd.isStatic()) {
                continue;
            }
            for (VariableDeclarator vd : fd.getVariables()) {
                names.add(vd.getNameAsString());
            }
        }
        return names;
    }

    @Override
    public void visit(MethodDeclaration n, Void arg) {
        if (n.getBody().isEmpty()) {
            super.visit(n, arg);
            return;
        }
        analyzeCallable(n, () -> super.visit(n, arg));
    }

    @Override
    public void visit(ConstructorDeclaration n, Void arg) {
        analyzeCallable(n, () -> super.visit(n, arg));
    }

    private void analyzeCallable(CallableDeclaration<?> declaration, Runnable descend) {
        MethodFrame frame = new MethodFrame();
        methodFrame = frame;
        try {
            descend.run();
        } finally {
            methodFrame = null;
        }
        String signature = signatureOf(declaration);
        String key = typeQualifiedName + "#" + signature;
        String hash = HashService.normalizedMethodHash(declaration);
        methods.put(key, new SupplementalMetrics.LegacyMethodMetrics(hash,
                HalsteadMetrics.from(frame.operators, frame.operands),
                List.copyOf(frame.exceptionSmells), List.copyOf(frame.codeSmells),
                frame.accessedFields.stream().sorted().toList()));
        mergeInto(classOperators, frame.operators);
        mergeInto(classOperands, frame.operands);
    }

    @Override
    public void visit(CatchClause n, Void arg) {
        MethodFrame frame = methodFrame;
        if (frame != null) {
            String typeName = n.getParameter().getType().asString();
            int line = beginLine(n);
            if (isGenericExceptionType(typeName)) {
                frame.exceptionSmells.add(new ExceptionSmell(ExceptionSmell.Type.GENERIC_EXCEPTION_CATCH, typeName, line));
            }
            if (isSwallowed(n.getBody())) {
                frame.exceptionSmells.add(new ExceptionSmell(ExceptionSmell.Type.SWALLOWED_EXCEPTION, typeName, line));
            }
        }
        super.visit(n, arg);
    }

    @Override
    public void visit(AssignExpr n, Void arg) {
        detectStringConcatInLoop(n);
        recordOperator(n.getOperator().asString());
        super.visit(n, arg);
    }

    @Override
    public void visit(StringLiteralExpr n, Void arg) {
        recordOperand(n.getValue());
        detectHardcodedLiteral(n);
        super.visit(n, arg);
    }

    @Override
    public void visit(VariableDeclarator n, Void arg) {
        MethodFrame frame = methodFrame;
        if (frame != null) {
            recordOperand(n.getNameAsString());
            frame.localTypes.put(n.getNameAsString(), n.getType().asString());
            rawTypeSmell(n.getType(), beginLine(n)).ifPresent(frame.codeSmells::add);
        }
        super.visit(n, arg);
    }

    @Override
    public void visit(Parameter n, Void arg) {
        MethodFrame frame = methodFrame;
        if (frame != null) {
            recordOperand(n.getNameAsString());
            frame.localTypes.put(n.getNameAsString(), n.getType().asString());
            rawTypeSmell(n.getType(), beginLine(n)).ifPresent(frame.codeSmells::add);
        }
        super.visit(n, arg);
    }

    @Override
    public void visit(NameExpr n, Void arg) {
        recordOperand(n.getNameAsString());
        markFieldAccess(n.getNameAsString());
        super.visit(n, arg);
    }

    @Override
    public void visit(FieldAccessExpr n, Void arg) {
        recordOperand(n.getNameAsString());
        markFieldAccess(n.getNameAsString());
        super.visit(n, arg);
    }

    @Override
    public void visit(IfStmt n, Void arg) {
        recordOperator("if");
        super.visit(n, arg);
    }

    @Override
    public void visit(ForStmt n, Void arg) {
        recordOperator("for");
        withLoop(() -> super.visit(n, arg));
    }

    @Override
    public void visit(ForEachStmt n, Void arg) {
        recordOperator("foreach");
        withLoop(() -> super.visit(n, arg));
    }

    @Override
    public void visit(WhileStmt n, Void arg) {
        recordOperator("while");
        withLoop(() -> super.visit(n, arg));
    }

    @Override
    public void visit(DoStmt n, Void arg) {
        recordOperator("do");
        withLoop(() -> super.visit(n, arg));
    }

    @Override
    public void visit(SwitchStmt n, Void arg) {
        recordOperator("switch");
        super.visit(n, arg);
    }

    @Override
    public void visit(SwitchEntry n, Void arg) {
        recordOperator(n.getLabels().isEmpty() ? "default" : "case");
        super.visit(n, arg);
    }

    @Override
    public void visit(TryStmt n, Void arg) {
        recordOperator("try");
        super.visit(n, arg);
    }

    @Override
    public void visit(SynchronizedStmt n, Void arg) {
        recordOperator("synchronized");
        super.visit(n, arg);
    }

    @Override
    public void visit(BinaryExpr n, Void arg) {
        recordOperator(n.getOperator().asString());
        super.visit(n, arg);
    }

    @Override
    public void visit(UnaryExpr n, Void arg) {
        recordOperator(n.getOperator().asString());
        super.visit(n, arg);
    }

    @Override
    public void visit(ReturnStmt n, Void arg) {
        recordOperator("return");
        super.visit(n, arg);
    }

    @Override
    public void visit(ThrowStmt n, Void arg) {
        recordOperator("throw");
        super.visit(n, arg);
    }

    @Override
    public void visit(BreakStmt n, Void arg) {
        recordOperator("break");
        super.visit(n, arg);
    }

    @Override
    public void visit(ContinueStmt n, Void arg) {
        recordOperator("continue");
        super.visit(n, arg);
    }

    @Override
    public void visit(MethodCallExpr n, Void arg) {
        recordOperator("call:" + n.getNameAsString());
        super.visit(n, arg);
    }

    @Override
    public void visit(ObjectCreationExpr n, Void arg) {
        recordOperator("new:" + n.getType().getNameAsString());
        super.visit(n, arg);
    }

    @Override
    public void visit(ArrayCreationExpr n, Void arg) {
        recordOperator("new[]");
        super.visit(n, arg);
    }

    @Override
    public void visit(ArrayAccessExpr n, Void arg) {
        recordOperator("[]");
        super.visit(n, arg);
    }

    @Override
    public void visit(CastExpr n, Void arg) {
        recordOperator("(cast)");
        super.visit(n, arg);
    }

    @Override
    public void visit(InstanceOfExpr n, Void arg) {
        recordOperator("instanceof");
        super.visit(n, arg);
    }

    @Override
    public void visit(IntegerLiteralExpr n, Void arg) {
        recordOperand(n.getValue());
        super.visit(n, arg);
    }

    @Override
    public void visit(LongLiteralExpr n, Void arg) {
        recordOperand(n.getValue());
        super.visit(n, arg);
    }

    @Override
    public void visit(DoubleLiteralExpr n, Void arg) {
        recordOperand(n.getValue());
        super.visit(n, arg);
    }

    @Override
    public void visit(CharLiteralExpr n, Void arg) {
        recordOperand(n.getValue());
        super.visit(n, arg);
    }

    @Override
    public void visit(BooleanLiteralExpr n, Void arg) {
        recordOperand(String.valueOf(n.getValue()));
        super.visit(n, arg);
    }

    @Override
    public void visit(NullLiteralExpr n, Void arg) {
        recordOperand("null");
        super.visit(n, arg);
    }

    private void recordOperator(String key) {
        MethodFrame frame = methodFrame;
        if (frame != null) {
            frame.operators.merge(key, 1, Integer::sum);
        }
    }

    private void recordOperand(String key) {
        MethodFrame frame = methodFrame;
        if (frame != null) {
            frame.operands.merge(key, 1, Integer::sum);
        }
    }

    private void withLoop(Runnable run) {
        MethodFrame frame = methodFrame;
        if (frame != null) {
            frame.loopDepth++;
        }
        try {
            run.run();
        } finally {
            if (frame != null) {
                frame.loopDepth--;
            }
        }
    }

    private void markFieldAccess(String name) {
        MethodFrame frame = methodFrame;
        if (frame != null && fieldNames.contains(name)) {
            frame.accessedFields.add(name);
        }
    }

    private void detectStringConcatInLoop(AssignExpr n) {
        MethodFrame frame = methodFrame;
        if (frame == null || frame.loopDepth <= 0) {
            return;
        }
        if (!(n.getTarget() instanceof NameExpr targetName)) {
            return;
        }
        String name = targetName.getNameAsString();
        if (!"String".equals(frame.localTypes.get(name))) {
            return;
        }
        boolean plusAssign = n.getOperator() == AssignExpr.Operator.PLUS;
        boolean selfConcat = n.getOperator() == AssignExpr.Operator.ASSIGN
                && n.getValue() instanceof BinaryExpr be
                && be.getOperator() == BinaryExpr.Operator.PLUS
                && be.getLeft() instanceof NameExpr leftName
                && leftName.getNameAsString().equals(name);
        if (plusAssign || selfConcat) {
            frame.codeSmells.add(new CodeSmell(CodeSmell.Type.STRING_CONCAT_IN_LOOP, beginLine(n),
                    "String concatenation on '" + name + "' inside a loop"));
        }
    }

    private void detectHardcodedLiteral(StringLiteralExpr n) {
        String value = n.getValue();
        CodeSmell.Type type;
        if (IP_PATTERN.matcher(value).find()) {
            type = CodeSmell.Type.HARDCODED_IP;
        } else if (SQL_PATTERN.matcher(value).find()) {
            type = CodeSmell.Type.HARDCODED_SQL;
        } else if (URL_PATTERN.matcher(value).find()) {
            type = CodeSmell.Type.HARDCODED_URL;
        } else {
            return;
        }
        MethodFrame frame = methodFrame;
        if (frame != null) {
            frame.codeSmells.add(new CodeSmell(type, beginLine(n), truncate(value)));
        }
    }

    private static Optional<CodeSmell> rawTypeSmell(Type type, int line) {
        if (type instanceof ClassOrInterfaceType cit
                && GENERIC_CAPABLE_TYPES.contains(cit.getNameAsString())
                && cit.getTypeArguments().isEmpty()) {
            return Optional.of(new CodeSmell(CodeSmell.Type.RAW_TYPE, line, "Raw type: " + cit.getNameAsString()));
        }
        return Optional.empty();
    }

    private static boolean isGenericExceptionType(String typeName) {
        return typeName.equals("Exception") || typeName.equals("Throwable")
                || typeName.equals("java.lang.Exception") || typeName.equals("java.lang.Throwable");
    }

    private static boolean isSwallowed(BlockStmt body) {
        List<Statement> statements = body.getStatements();
        return statements.isEmpty() || statements.stream().allMatch(LegacyAstMetricsVisitor::isNoiseOnlyStatement);
    }

    private static boolean isNoiseOnlyStatement(Statement statement) {
        if (!statement.isExpressionStmt()) {
            return false;
        }
        Expression expression = statement.asExpressionStmt().getExpression();
        if (!(expression instanceof MethodCallExpr call)) {
            return false;
        }
        String name = call.getNameAsString();
        if (name.equals("printStackTrace")) {
            return true;
        }
        if (!name.equals("println") && !name.equals("print")) {
            return false;
        }
        return call.getScope()
                .filter(scope -> scope instanceof FieldAccessExpr)
                .map(scope -> (FieldAccessExpr) scope)
                .filter(fa -> fa.getNameAsString().equals("out") || fa.getNameAsString().equals("err"))
                .map(FieldAccessExpr::getScope)
                .filter(scope -> scope instanceof NameExpr ne && ne.getNameAsString().equals("System"))
                .isPresent();
    }

    private static void mergeInto(Map<String, Integer> target, Map<String, Integer> source) {
        source.forEach((k, v) -> target.merge(k, v, Integer::sum));
    }

    private static String signatureOf(CallableDeclaration<?> declaration) {
        String parameters = declaration.getParameters().stream()
                .map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : ""))
                .collect(Collectors.joining(", "));
        return declaration.getNameAsString() + "(" + parameters + ")";
    }

    private static String truncate(String value) {
        return value.length() > 80 ? value.substring(0, 77) + "..." : value;
    }

    private static int beginLine(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }

    private static final class MethodFrame {
        private int loopDepth;
        private final Map<String, Integer> operators = new LinkedHashMap<>();
        private final Map<String, Integer> operands = new LinkedHashMap<>();
        private final Map<String, String> localTypes = new LinkedHashMap<>();
        private final Set<String> accessedFields = new HashSet<>();
        private final List<ExceptionSmell> exceptionSmells = new ArrayList<>();
        private final List<CodeSmell> codeSmells = new ArrayList<>();
    }
}
