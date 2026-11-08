package com.scanner.complexity;

/** Caller context for filtering outbound call keys. */
record OutboundCallContext(
        String enclosingQualifiedType,
        String enclosingSimpleType,
        ImportTypeIndex imports,
        java.util.Map<String, String> localReceiverTypes) {

    static OutboundCallContext forEnclosingType(String typeStackName, ImportTypeIndex imports) {
        return forEnclosingType(typeStackName, imports, java.util.Map.of());
    }

    static OutboundCallContext forEnclosingType(String typeStackName, ImportTypeIndex imports,
                                                java.util.Map<String, String> localReceiverTypes) {
        String simple = simpleName(typeStackName);
        String qualified = imports.resolveSimple(simple);
        if (qualified == null || qualified.equals(simple)) {
            qualified = imports.packageName().isEmpty()
                    ? simple
                    : imports.packageName() + "." + simple;
        }
        return new OutboundCallContext(qualified, simple, imports,
                localReceiverTypes == null ? java.util.Map.of() : localReceiverTypes);
    }

    String resolveReceiverSimpleName(String simple) {
        if (simple == null || simple.isEmpty()) {
            return null;
        }
        if (localReceiverTypes != null && localReceiverTypes.containsKey(simple)) {
            return localReceiverTypes.get(simple);
        }
        return imports.resolveSimple(simple);
    }

    private static String simpleName(String typeStackName) {
        if (typeStackName == null || typeStackName.isEmpty()) {
            return "";
        }
        int dot = typeStackName.lastIndexOf('.');
        return dot >= 0 ? typeStackName.substring(dot + 1) : typeStackName;
    }

    boolean isSameType(String resolvedType) {
        if (resolvedType == null || resolvedType.isEmpty()) {
            return false;
        }
        if (resolvedType.equals(enclosingQualifiedType)) {
            return true;
        }
        return resolvedType.endsWith("." + enclosingSimpleType)
                || resolvedType.equals(enclosingSimpleType);
    }
}
