package com.scanner.complexity;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps simple type names to FQCN using imports and same-package types (no symbol solver). */
final class ImportTypeIndex {

    private static final Set<String> JDK_SIMPLE = Set.of(
            "String", "Object", "Class", "Void", "Boolean", "Integer", "Long", "Double", "Float",
            "Short", "Byte", "Character", "Number", "Enum", "Iterable", "Comparable",
            "Exception", "RuntimeException", "Throwable", "Error", "System", "Math",
            "List", "Map", "Set", "Collection", "Optional", "Stream", "Arrays", "Collections",
            "Objects", "UUID", "Locale", "Pattern", "StringBuilder", "StringBuffer");

    private final String packageName;
    private final Map<String, String> simpleToFqcn = new HashMap<>();
    private final Set<String> samePackageTypes = new HashSet<>();

    private ImportTypeIndex(String packageName) {
        this.packageName = packageName == null ? "" : packageName;
    }

    static ImportTypeIndex of(CompilationUnit unit) {
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        ImportTypeIndex index = new ImportTypeIndex(pkg);
        unit.getImports().forEach(importDecl -> {
            if (importDecl.isStatic()) {
                return;
            }
            String name = importDecl.getNameAsString();
            if (importDecl.isAsterisk()) {
                return;
            }
            int dot = name.lastIndexOf('.');
            String simple = dot >= 0 ? name.substring(dot + 1) : name;
            index.simpleToFqcn.put(simple, name);
        });
        unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).forEach(type -> {
            index.samePackageTypes.add(type.getNameAsString());
            index.simpleToFqcn.putIfAbsent(type.getNameAsString(),
                    pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString());
        });
        return index;
    }

    String packageName() {
        return packageName;
    }

    String resolveTypeName(ClassOrInterfaceType type) {
        if (type == null) {
            return null;
        }
        String raw = type.asString();
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        raw = raw.trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.contains(".")) {
            return raw;
        }
        return resolveSimple(raw);
    }

    String resolveSimple(String simple) {
        if (simple == null || simple.isEmpty()) {
            return null;
        }
        String mapped = simpleToFqcn.get(simple);
        if (mapped != null) {
            return mapped;
        }
        if (!packageName.isEmpty() && samePackageTypes.contains(simple)) {
            return packageName + "." + simple;
        }
        return null;
    }

    static boolean isJdkOrUtilityType(String fqcnOrSimple) {
        if (fqcnOrSimple == null || fqcnOrSimple.isEmpty()) {
            return true;
        }
        String name = fqcnOrSimple;
        if (name.startsWith("java.lang.") || name.startsWith("java.util.")
                || name.startsWith("java.time.") || name.startsWith("java.math.")
                || name.startsWith("java.nio.") || name.startsWith("java.net.")
                || name.startsWith("javax.")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        String simple = dot >= 0 ? name.substring(dot + 1) : name;
        return JDK_SIMPLE.contains(simple);
    }
}
