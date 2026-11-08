package com.scanner.complexity;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Syntactic efferent-coupling (Ce) estimation: the set of distinct type names a class or
 * interface declaration textually references (field types, parameter types, return types, local
 * variable types, generic type arguments, casts, {@code instanceof} checks, thrown types,
 * {@code new} expressions, {@code extends}/{@code implements}).
 *
 * <p>No symbol solver or classpath is used (this is a standalone, offline analyzer), so this is a
 * name-based approximation rather than a fully resolved dependency graph: two distinct types that
 * share a simple name are merged into one, and wildcard imports cannot be expanded. It is
 * nevertheless a practical, classpath-free signal that correlates well with true efferent
 * coupling.</p>
 */
final class TypeDependencyAnalyzer {

    private TypeDependencyAnalyzer() {
    }

    static Set<String> collectEfferentTypes(TypeDeclaration<?> declaration) {
        Set<String> selfNames = new HashSet<>();
        selfNames.add(declaration.getNameAsString());
        declaration.findAll(TypeDeclaration.class).forEach(t -> selfNames.add(t.getNameAsString()));

        Set<String> typeParameters = new HashSet<>();
        if (declaration instanceof ClassOrInterfaceDeclaration cid) {
            cid.getTypeParameters().forEach(tp -> typeParameters.add(tp.getNameAsString()));
        }
        declaration.findAll(TypeParameter.class).forEach(tp -> typeParameters.add(tp.getNameAsString()));

        return declaration.findAll(ClassOrInterfaceType.class).stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .filter(name -> !selfNames.contains(name) && !typeParameters.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
