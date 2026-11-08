package com.scanner.complexity;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;

import java.util.HashSet;
import java.util.Set;

/** CK Ce proxy: distinct external type names (import-aware, JDK filtered). */
final class ClassEfferentCoupling {

    private ClassEfferentCoupling() {
    }

    static int distinctReferencedTypes(TypeDeclaration<?> type, CompilationUnit unit) {
        ImportTypeIndex index = unit == null ? null : ImportTypeIndex.of(unit);
        String enclosing = enclosingQualified(type, index);
        return distinctReferencedTypes((Node) type, index, enclosing);
    }

    static int distinctReferencedTypes(Node root, CompilationUnit unit) {
        ImportTypeIndex index = unit == null ? null : ImportTypeIndex.of(unit);
        return distinctReferencedTypes(root, index, null);
    }

    static int distinctReferencedTypes(Node root, ImportTypeIndex index, String enclosingQualified) {
        Set<String> keys = new HashSet<>();
        root.findAll(ClassOrInterfaceType.class).forEach(type -> addType(keys, type, index, enclosingQualified));
        root.findAll(ObjectCreationExpr.class)
                .forEach(creation -> addType(keys, creation.getType(), index, enclosingQualified));
        if (index != null) {
            addMatchingImports(keys, root, index, enclosingQualified);
        }
        return keys.size();
    }

    private static String enclosingQualified(TypeDeclaration<?> type, ImportTypeIndex index) {
        if (index == null) {
            return type.getNameAsString();
        }
        return index.resolveSimple(type.getNameAsString());
    }

    private static void addMatchingImports(Set<String> keys, Node root, ImportTypeIndex index,
                                           String enclosingQualified) {
        Set<String> names = new HashSet<>();
        root.findAll(NameExpr.class).forEach(name -> names.add(name.getNameAsString()));
        for (String simple : names) {
            String fqcn = index.resolveSimple(simple);
            if (fqcn != null && !fqcn.equals(simple) && includeType(fqcn, enclosingQualified)) {
                keys.add(fqcn);
            }
        }
    }

    private static void addType(Set<String> keys, Type type, ImportTypeIndex index, String enclosingQualified) {
        if (type instanceof PrimitiveType) {
            return;
        }
        if (type instanceof ClassOrInterfaceType classType) {
            String resolved = index != null ? index.resolveTypeName(classType) : rawSimple(classType);
            if (resolved != null && includeType(resolved, enclosingQualified)) {
                keys.add(resolved);
            }
        }
    }

    private static String rawSimple(ClassOrInterfaceType classType) {
        String raw = classType.asString();
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        return raw.trim();
    }

    private static boolean includeType(String resolved, String enclosingQualified) {
        if (resolved.isEmpty() || ImportTypeIndex.isJdkOrUtilityType(resolved)) {
            return false;
        }
        if (enclosingQualified != null && (resolved.equals(enclosingQualified)
                || resolved.endsWith("." + simpleName(enclosingQualified)))) {
            return false;
        }
        return true;
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }
}
