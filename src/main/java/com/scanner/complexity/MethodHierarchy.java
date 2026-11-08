package com.scanner.complexity;

import java.util.ArrayList;
import java.util.List;

/** Dosya → paket → sınıf → metod ata zinciri (monorepo / modül kaybı olmasın). */
final class MethodHierarchy {

    private MethodHierarchy() {
    }

    /** Göreli yoldan yalnızca dosya adı (`Foo.java`). */
    static String javaFileName(String relativeFilePath) {
        if (relativeFilePath == null || relativeFilePath.isEmpty()) {
            return "";
        }
        int slash = Math.max(relativeFilePath.lastIndexOf('/'), relativeFilePath.lastIndexOf('\\'));
        return slash >= 0 ? relativeFilePath.substring(slash + 1) : relativeFilePath;
    }

    static String moduleRoot(String relativeFilePath) {
        return moduleRoot(null, relativeFilePath);
    }

    static String moduleRoot(ModuleRootIndex index, String relativeFilePath) {
        if (index != null) {
            return index.moduleRoot(relativeFilePath);
        }
        if (relativeFilePath == null || relativeFilePath.isEmpty()) {
            return "";
        }
        int slash = relativeFilePath.indexOf('/');
        return slash > 0 ? relativeFilePath.substring(0, slash) : relativeFilePath;
    }

    static String qualifiedClass(String packageName, String className) {
        return packageName == null || packageName.isEmpty() ? className : packageName + "." + className;
    }

    static List<String> ancestorPath(String relativeFilePath, String packageName, String className,
                                       String methodSignature) {
        return ancestorPath(null, relativeFilePath, packageName, className, methodSignature);
    }

    static List<String> ancestorPath(ModuleRootIndex index, String relativeFilePath, String packageName,
                                   String className, String methodSignature) {
        List<String> chain = new ArrayList<>(4);
        chain.add(moduleRoot(index, relativeFilePath));
        chain.add(relativeFilePath);
        chain.add(qualifiedClass(packageName, className));
        chain.add(methodSignature);
        return List.copyOf(chain);
    }

    static String breadcrumb(List<String> ancestorPath) {
        return String.join(" › ", ancestorPath);
    }

    /** Tam nitelikli sınıf + metod imzası (JSON / arama). */
    static String compactMethodLabel(String packageName, String className, String methodSignature) {
        return qualifiedClass(packageName, className) + "." + methodSignature;
    }

    /** Ana tablo: sınıf (servis) adı + metod imzası — paket ve dosya yolu yok. */
    static String shortMethodLabel(String className, String methodSignature) {
        return className + "." + methodSignature;
    }

    /** Ana tablo: paket/dosya yok — {@code SınıfAdı.metod(…n param)}. */
    static String tableMethodLabel(String className, String methodSignature) {
        return simpleClassName(className) + "." + compactMethodSignature(methodSignature);
    }

    /** Tablo sütunu: iç içe sınıfta son segment (`Outer.Inner` → `Inner`). */
    static String simpleClassName(String className) {
        if (className == null || className.isEmpty()) {
            return "";
        }
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    /**
     * Uzun generic imzaları kısaltır: {@code detect(…4 param)} — tam imza hiyerarşi listesinde.
     */
    static String compactMethodSignature(String methodSignature) {
        if (methodSignature == null || methodSignature.isEmpty()) {
            return "";
        }
        int open = methodSignature.indexOf('(');
        if (open < 0) {
            return methodSignature;
        }
        String name = methodSignature.substring(0, open);
        int close = methodSignature.lastIndexOf(')');
        String inside = close > open ? methodSignature.substring(open + 1, close).trim() : "";
        if (inside.isEmpty()) {
            return name + "()";
        }
        int params = countTopLevelCommas(inside) + 1;
        return name + "(…" + params + " param)";
    }

    private static int countTopLevelCommas(String paramList) {
        int angleDepth = 0;
        int commas = 0;
        for (int i = 0; i < paramList.length(); i++) {
            char c = paramList.charAt(i);
            if (c == '<') {
                angleDepth++;
            } else if (c == '>' && angleDepth > 0) {
                angleDepth--;
            } else if (c == ',' && angleDepth == 0) {
                commas++;
            }
        }
        return commas;
    }
}
