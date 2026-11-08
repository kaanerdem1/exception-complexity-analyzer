package com.scanner.complexity;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.HashMap;
import java.util.Map;

/** Parameter and field names → resolved types for outbound call receivers (no symbol solver). */
final class LocalReceiverTypes {

    private LocalReceiverTypes() {
    }

    static Map<String, String> forCallable(CallableDeclaration<?> callable, ImportTypeIndex index) {
        Map<String, String> map = new HashMap<>();
        if (index == null) {
            return Map.of();
        }
        callable.getParameters().forEach(p -> bind(map, p.getNameAsString(), p.getType(), index));
        callable.findAncestor(TypeDeclaration.class).ifPresent(type ->
                type.findAll(FieldDeclaration.class).forEach(field ->
                        field.getVariables().forEach(v ->
                                bind(map, v.getNameAsString(), field.getCommonType(), index))));
        return Map.copyOf(map);
    }

    private static void bind(Map<String, String> map, String name, Type type, ImportTypeIndex index) {
        String fqcn = resolveClassType(type, index);
        if (fqcn != null && !ImportTypeIndex.isJdkOrUtilityType(fqcn)) {
            map.put(name, fqcn);
        }
    }

    private static String resolveClassType(Type type, ImportTypeIndex index) {
        if (type == null) {
            return null;
        }
        if (type.isClassOrInterfaceType()) {
            return index.resolveTypeName(type.asClassOrInterfaceType());
        }
        if (type.isArrayType()) {
            return resolveClassType(type.asArrayType().getComponentType(), index);
        }
        return null;
    }
}
