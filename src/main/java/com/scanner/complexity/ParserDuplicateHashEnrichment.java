package com.scanner.complexity;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.scanner.complexity.AnalysisReport.ClassMetric;
import com.scanner.complexity.AnalysisReport.FileMetric;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PMD metrik raporuna parser ile aynı {@code bodyHash} / {@code structuralHash} ekler (duplicate tespiti için).
 */
final class ParserDuplicateHashEnrichment {

    private ParserDuplicateHashEnrichment() {
    }

    static List<FileMetric> enrich(Path sourceFile, String relativePath, String sourceText, FileMetric pmdFile,
                                   Charset charset, ParserConfiguration.LanguageLevel languageLevel) throws IOException {
        SourceParser.Outcome outcome = SourceParser.parse(sourceFile, charset, languageLevel);
        if (!outcome.result().isSuccessful() || outcome.result().getResult().isEmpty()) {
            return List.of(pmdFile);
        }
        CompilationUnit cu = outcome.result().getResult().get();
        Map<MethodKey, Hashes> hashes = indexHashes(cu);
        List<ClassMetric> enrichedTypes = new ArrayList<>();
        for (ClassMetric type : pmdFile.classes()) {
            List<MethodMetric> methods = new ArrayList<>();
            for (MethodMetric method : type.methods()) {
                String simpleType = simpleName(type.name());
                Hashes h = lookupHashes(hashes, simpleType, method.name(), method.startLine());
                if (h == null) {
                    methods.add(method);
                    continue;
                }
                methods.add(copyWithHashes(method, h));
            }
            enrichedTypes.add(copyClassWithMethods(type, methods));
        }
        return List.of(new FileMetric(pmdFile.path(), pmdFile.packageName(), pmdFile.physicalLines(), pmdFile.codeLines(),
                pmdFile.classCount(), pmdFile.methodCount(), pmdFile.totalCyclomaticComplexity(),
                pmdFile.maxCyclomaticComplexity(), pmdFile.riskScore(), pmdFile.riskLevel(), pmdFile.riskFactors(),
                enrichedTypes, pmdFile.halstead(), pmdFile.swallowedExceptionCount(),
                pmdFile.genericExceptionCatchCount(), pmdFile.rawTypeUsageCount(), pmdFile.stringConcatInLoopCount(),
                pmdFile.hardcodedLiteralCount(), pmdFile.fileHash(), pmdFile.fullyReusedFromCache(),
                pmdFile.methodsReusedFromCache()));
    }

    private record MethodKey(String typeSimpleName, String methodName, int startLine) {
    }

    private static Hashes lookupHashes(Map<MethodKey, Hashes> hashes, String type, String methodName, int startLine) {
        Hashes exact = hashes.get(new MethodKey(type, methodName, startLine));
        if (exact != null) {
            return exact;
        }
        for (int delta : new int[] {-1, 1, -2, 2}) {
            Hashes near = hashes.get(new MethodKey(type, methodName, startLine + delta));
            if (near != null) {
                return near;
            }
        }
        return null;
    }

    private record Hashes(String bodyHash, String structuralHash) {
    }

    private static Map<MethodKey, Hashes> indexHashes(CompilationUnit cu) {
        Map<MethodKey, Hashes> map = new HashMap<>();
        cu.findAll(BodyDeclaration.class).stream()
                .filter(b -> b instanceof MethodDeclaration || b instanceof ConstructorDeclaration)
                .map(b -> (CallableDeclaration<?>) b)
                .forEach(callable -> callable.getRange().ifPresent(range -> {
                String typeSimple = callable.findAncestor(TypeDeclaration.class)
                        .map(TypeDeclaration::getNameAsString)
                        .orElse("");
                BlockStmt body = callable instanceof MethodDeclaration md
                        ? md.getBody().orElse(null)
                        : ((ConstructorDeclaration) callable).getBody();
                String bodyHash = body != null ? HashService.normalizedBodyHash(body) : "";
                String structuralHash = body != null ? HashService.structuralHash(body) : "";
                map.put(new MethodKey(typeSimple, callable.getNameAsString(), range.begin.line),
                        new Hashes(bodyHash, structuralHash));
            }));
        return map;
    }

    private static MethodMetric copyWithHashes(MethodMetric method, Hashes hashes) {
        return new MethodMetric(method.name(), method.kind(), method.signature(), method.returnType(),
                method.startLine(), method.endLine(), method.cyclomaticComplexity(), method.physicalLines(),
                method.codeLines(), method.logicalStatements(), method.cognitiveComplexity(), method.exitPoints(),
                method.catchClauses(), method.switchCases(), method.outboundDistinctCalls(), method.lambdaCount(),
                method.maxTryNestingDepth(), method.localVariableCount(), method.maxMethodCallChainLength(),
                method.emptyCatchBlocks(), method.catchExceptionOrThrowable(), method.catchWithOnlyPrintStackTrace(),
                method.primitiveObsessionIndex(), method.maxBooleanOperatorsInCondition(), method.maxNestingDepth(),
                method.parameterCount(), method.godMethod(), method.riskScore(), method.riskLevel(),
                method.riskFactors(), method.riskBreakdown(), method.halstead(), method.exceptionSmells(),
                method.codeSmells(), method.methodHash(), method.analysisReused(), method.accessedFieldNames(),
                hashes.bodyHash(), hashes.structuralHash(), method.npathComplexity(), method.ncssCount(),
                method.metricsImputed());
    }

    private static ClassMetric copyClassWithMethods(ClassMetric type, List<MethodMetric> methods) {
        return new ClassMetric(type.name(), type.kind(), type.startLine(), type.endLine(), methods.size(),
                type.publicMethodCount(), type.codeLines(), type.efferentCouplingProxy(), type.weightedMethodComplexity(),
                type.maxMethodComplexity(), type.averageMethodComplexity(), type.riskScore(), type.riskLevel(),
                type.riskFactors(), methods, type.halstead(), type.dependentTypes(), type.lcom3(),
                type.godClassCandidate(), type.swallowedExceptionCount(), type.genericExceptionCatchCount(),
                type.rawTypeUsageCount(), type.stringConcatInLoopCount(), type.hardcodedLiteralCount());
    }

    private static String simpleName(String fqName) {
        int dot = fqName.lastIndexOf('.');
        return dot >= 0 ? fqName.substring(dot + 1) : fqName;
    }
}
