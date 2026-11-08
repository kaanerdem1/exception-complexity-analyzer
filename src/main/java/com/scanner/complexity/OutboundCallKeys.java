package com.scanner.complexity;

import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.Optional;

/** External outbound call keys (import-aware, JDK/same-type filtered). */
final class OutboundCallKeys {

    private OutboundCallKeys() {
    }

    static Optional<String> externalCallKey(MethodCallExpr call, OutboundCallContext ctx) {
        if (ctx == null || ctx.imports() == null) {
            return Optional.empty();
        }
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = call.getScope().get();
        if (scope.isThisExpr()) {
            return Optional.empty();
        }
        String receiverType = receiverType(scope, ctx);
        if (receiverType == null || ctx.isSameType(receiverType)
                || ImportTypeIndex.isJdkOrUtilityType(receiverType)) {
            return Optional.empty();
        }
        return Optional.of(receiverType + "#" + call.getNameAsString());
    }

    static Optional<String> externalCreationKey(ObjectCreationExpr creation, OutboundCallContext ctx) {
        if (ctx == null || ctx.imports() == null) {
            return Optional.empty();
        }
        String type = ctx.imports().resolveTypeName(creation.getType());
        if (type == null || ctx.isSameType(type) || ImportTypeIndex.isJdkOrUtilityType(type)) {
            return Optional.empty();
        }
        return Optional.of("new:" + type);
    }

    static int methodCallChainLength(MethodCallExpr call) {
        int length = 1;
        var scope = call.getScope();
        while (scope.isPresent()) {
            Expression expression = scope.get();
            if (expression instanceof MethodCallExpr nested) {
                length++;
                scope = nested.getScope();
            } else if (expression instanceof FieldAccessExpr field) {
                length++;
                scope = optionalScope(field.getScope());
            } else {
                break;
            }
        }
        return length;
    }

    private static String receiverType(Expression expr, OutboundCallContext ctx) {
        if (expr == null) {
            return null;
        }
        if (expr.isNameExpr()) {
            return ctx.resolveReceiverSimpleName(expr.asNameExpr().getNameAsString());
        }
        if (expr.isFieldAccessExpr()) {
            FieldAccessExpr field = expr.asFieldAccessExpr();
            Expression scope = field.getScope();
            if (scope != null) {
                if (scope.isNameExpr()) {
                    String base = ctx.resolveReceiverSimpleName(scope.asNameExpr().getNameAsString());
                    return base == null ? null : base + "." + field.getNameAsString();
                }
                if (scope.isClassExpr()) {
                    return typeFromClassExpr(scope.asClassExpr());
                }
                return receiverType(scope, ctx);
            }
            return ctx.imports().resolveSimple(field.getNameAsString());
        }
        if (expr.isMethodCallExpr()) {
            return receiverType(expr.asMethodCallExpr().getScope().orElse(null), ctx);
        }
        if (expr.isClassExpr()) {
            return typeFromClassExpr(expr.asClassExpr());
        }
        if (expr.isEnclosedExpr()) {
            return receiverType(expr.asEnclosedExpr().getInner(), ctx);
        }
        if (expr.isObjectCreationExpr()) {
            return ctx.imports().resolveTypeName(expr.asObjectCreationExpr().getType());
        }
        return null;
    }

    private static String typeFromClassExpr(ClassExpr classExpr) {
        return classExpr.getType().asString();
    }

    private static Optional<Expression> optionalScope(Expression scope) {
        return scope == null ? Optional.empty() : Optional.of(scope);
    }
}
