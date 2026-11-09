package com.scanner.complexity;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Loads {@link RiskProfile} from {@code config/risk-parameters-proposal.yaml}. */
public final class RiskProfileLoader {

    /** Varsayılan CLI / {@link RiskCalculator} profili (YAML {@code enterprise-java}). */
    public static final String DEFAULT_PROFILE_ID = "enterprise-java-pmd";

    private static final String DEFAULT_RESOURCE = "/config/risk-parameters-proposal.yaml";
    private static final double DEFAULT_COMPOUND = 0.15;

    private RiskProfileLoader() {
    }

    public static RiskProfile load(String profileId, Path configPath) throws IOException {
        Map<String, Object> root;
        try (InputStream in = openConfig(configPath)) {
            root = new Yaml().load(in);
        }
        if (root == null) {
            throw new IOException("Empty risk config");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> profiles = (Map<String, Object>) root.get("profiles");
        if (profiles == null || !profiles.containsKey(profileId)) {
            throw new IllegalArgumentException("Unknown risk profile: " + profileId);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) profiles.get(profileId);
        String modelVersion = stringOr(profile.get("model_version"), "v2");
        double compound = compoundFactor(root, profile);
        ScoreMix mix = scoreMix(root, profile, compound);

        @SuppressWarnings("unchecked")
        Map<String, Object> dimensions = (Map<String, Object>) profile.get("dimensions");
        @SuppressWarnings("unchecked")
        Map<String, Object> weights = (Map<String, Object>) profile.get("blend_weights");

        List<RiskProfile.ScoredDimension<MethodScanValues>> methodDims = new ArrayList<>();
        if (dimensions != null) {
            for (Map.Entry<String, Object> entry : dimensions.entrySet()) {
                String id = entry.getKey();
                MethodDimensionSpec spec = METHOD_SPECS.get(id);
                if (spec == null) {
                    continue;
                }
                RiskProfile.ThresholdTriple triple = readThresholds(entry.getValue());
                double weight = weightOf(weights, id, 1.0);
                methodDims.add(new RiskProfile.ScoredDimension<>(
                        id, spec.label(), weight, triple, spec.extractor()));
            }
        }
        normalizeMethodWeights(methodDims);

        @SuppressWarnings("unchecked")
        Map<String, Object> classDimensions = (Map<String, Object>) profile.get("class_dimensions");
        @SuppressWarnings("unchecked")
        Map<String, Object> classWeights = (Map<String, Object>) profile.get("class_blend_weights");
        List<RiskProfile.ScoredDimension<RiskProfile.ClassScanValues>> classDims = new ArrayList<>();
        if (classDimensions != null) {
            for (Map.Entry<String, Object> entry : classDimensions.entrySet()) {
                ClassDimensionSpec spec = CLASS_SPECS.get(entry.getKey());
                if (spec == null) {
                    continue;
                }
                RiskProfile.ThresholdTriple triple = readThresholds(entry.getValue());
                double weight = weightOf(classWeights, entry.getKey(), 1.0);
                classDims.add(new RiskProfile.ScoredDimension<>(
                        entry.getKey(), spec.label(), weight, triple, spec.extractor()));
            }
        }
        normalizeClassWeights(classDims);

        List<RiskProfile.DimensionGroup> methodGroups = readDimensionGroups(profile);

        Map<String, RiskProfile.ThresholdTriple> god = new LinkedHashMap<>();
        god.put("branching", findThreshold(methodDims, "branching").orElse(new RiskProfile.ThresholdTriple(10, 15, 25)));
        god.put("length", findThreshold(methodDims, "length").orElse(new RiskProfile.ThresholdTriple(25, 55, 120)));
        god.put("parameters", findThreshold(methodDims, "parameters").orElse(new RiskProfile.ThresholdTriple(4, 6, 8)));

        List<RiskProfile.MethodLocScoreCap> locCaps = readMethodLocScoreCaps(root, profile);

        return new RiskProfile(profileId, modelVersion, compound, mix.dominant(), mix.blend(),
                List.copyOf(methodDims), List.copyOf(methodGroups), List.copyOf(classDims), Map.copyOf(god),
                locCaps);
    }

    @SuppressWarnings("unchecked")
    private static List<RiskProfile.MethodLocScoreCap> readMethodLocScoreCaps(Map<String, Object> root,
                                                                              Map<String, Object> profile) {
        Object node = profile.get("method_loc_score_caps");
        if (node == null) {
            node = root.get("method_loc_score_caps");
        }
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<RiskProfile.MethodLocScoreCap> caps = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object lines = map.get("max_code_lines");
            Object score = map.get("max_score");
            if (!(lines instanceof Number linesN) || !(score instanceof Number scoreN)) {
                continue;
            }
            caps.add(new RiskProfile.MethodLocScoreCap(linesN.intValue(), scoreN.doubleValue()));
        }
        caps.sort(java.util.Comparator.comparingInt(RiskProfile.MethodLocScoreCap::maxCodeLinesInclusive));
        return List.copyOf(caps);
    }

    @SuppressWarnings("unchecked")
    private static List<RiskProfile.DimensionGroup> readDimensionGroups(Map<String, Object> profile) {
        Object node = profile.get("dimension_groups");
        if (!(node instanceof Map<?, ?> groups)) {
            return List.of();
        }
        List<RiskProfile.DimensionGroup> out = new ArrayList<>();
        for (Map.Entry<?, ?> entry : groups.entrySet()) {
            String id = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                continue;
            }
            double weight = body.get("weight") instanceof Number n ? n.doubleValue() : 1.0;
            List<String> members = new ArrayList<>();
            if (body.get("members") instanceof List<?> list) {
                for (Object m : list) {
                    members.add(String.valueOf(m));
                }
            }
            if (!members.isEmpty()) {
                out.add(new RiskProfile.DimensionGroup(id, weight, List.copyOf(members)));
            }
        }
        double sum = out.stream().mapToDouble(RiskProfile.DimensionGroup::weight).sum();
        if (sum <= 0) {
            return List.copyOf(out);
        }
        List<RiskProfile.DimensionGroup> normalized = new ArrayList<>(out.size());
        for (RiskProfile.DimensionGroup g : out) {
            normalized.add(new RiskProfile.DimensionGroup(g.id(), g.weight() / sum, g.memberIds()));
        }
        return List.copyOf(normalized);
    }

    private record ScoreMix(double dominant, double blend) {
    }

    @SuppressWarnings("unchecked")
    private static ScoreMix scoreMix(Map<String, Object> root, Map<String, Object> profile, double compoundFallback) {
        Map<String, Object> mixNode = null;
        if (profile.get("score_mix") instanceof Map<?, ?> profileMix) {
            mixNode = (Map<String, Object>) profileMix;
        } else if (root.get("score_mix") instanceof Map<?, ?> rootMix) {
            mixNode = (Map<String, Object>) rootMix;
        }
        if (mixNode != null) {
            double dominant = numberOr(mixNode.get("dominant_weight"), -1);
            double blend = numberOr(mixNode.get("blend_weight"), -1);
            if (dominant > 0 && blend > 0) {
                return new ScoreMix(dominant, blend);
            }
            if (dominant > 0) {
                return new ScoreMix(dominant, 1.0 - dominant);
            }
            if (blend > 0) {
                return new ScoreMix(1.0 - blend, blend);
            }
        }
        double blend = compoundFallback > 0 ? compoundFallback : DEFAULT_COMPOUND;
        return new ScoreMix(1.0 - blend, blend);
    }

    private static double numberOr(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    public static Path defaultConfigPath() {
        Path cwd = Path.of("config", "risk-parameters-proposal.yaml");
        if (Files.isRegularFile(cwd)) {
            return cwd.toAbsolutePath().normalize();
        }
        return null;
    }

    private static InputStream openConfig(Path configPath) throws IOException {
        if (configPath != null && Files.isRegularFile(configPath)) {
            return Files.newInputStream(configPath);
        }
        InputStream resource = RiskProfileLoader.class.getResourceAsStream(DEFAULT_RESOURCE);
        if (resource == null) {
            throw new IOException("Risk config not found: " + configPath + " or classpath " + DEFAULT_RESOURCE);
        }
        return resource;
    }

    private static double compoundFactor(Map<String, Object> root, Map<String, Object> profile) {
        Object profileBlend = profile.get("compound_factor");
        if (profileBlend instanceof Number number) {
            return number.doubleValue();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> explain = (Map<String, Object>) root.get("explainable_model");
        if (explain != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> blend = (Map<String, Object>) explain.get("blend");
            if (blend != null && blend.get("ratio") instanceof Number ratio) {
                return ratio.doubleValue();
            }
        }
        return DEFAULT_COMPOUND;
    }

    private static RiskProfile.ThresholdTriple readThresholds(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected thresholds map, got: " + node);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> thresholds = (Map<String, Object>) map.get("thresholds");
        if (thresholds == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> asThresholds = (Map<String, Object>) map;
            thresholds = asThresholds;
        }
        return new RiskProfile.ThresholdTriple(
                intVal(thresholds, "dikkat"),
                intVal(thresholds, "yuksek"),
                intVal(thresholds, "kritik"));
    }

    private static int intVal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Missing threshold " + key + " in " + map);
    }

    private static double weightOf(Map<String, Object> weights, String id, double fallback) {
        if (weights == null || !weights.containsKey(id)) {
            return fallback;
        }
        Object value = weights.get(id);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static void normalizeMethodWeights(List<RiskProfile.ScoredDimension<MethodScanValues>> dims) {
        double sum = dims.stream().mapToDouble(RiskProfile.ScoredDimension::weight).sum();
        if (sum <= 0) {
            return;
        }
        for (int i = 0; i < dims.size(); i++) {
            RiskProfile.ScoredDimension<MethodScanValues> d = dims.get(i);
            dims.set(i, new RiskProfile.ScoredDimension<>(
                    d.id(), d.label(), d.weight() / sum, d.thresholds(), d.value()));
        }
    }

    private static void normalizeClassWeights(List<RiskProfile.ScoredDimension<RiskProfile.ClassScanValues>> dims) {
        double sum = dims.stream().mapToDouble(RiskProfile.ScoredDimension::weight).sum();
        if (sum <= 0) {
            return;
        }
        for (int i = 0; i < dims.size(); i++) {
            RiskProfile.ScoredDimension<RiskProfile.ClassScanValues> d = dims.get(i);
            dims.set(i, new RiskProfile.ScoredDimension<>(
                    d.id(), d.label(), d.weight() / sum, d.thresholds(), d.value()));
        }
    }

    private static java.util.Optional<RiskProfile.ThresholdTriple> findThreshold(
            List<RiskProfile.ScoredDimension<MethodScanValues>> dims, String id) {
        return dims.stream().filter(d -> d.id().equals(id)).findFirst().map(RiskProfile.ScoredDimension::thresholds);
    }

    private static String stringOr(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    private record MethodDimensionSpec(String label, ToIntFunction<MethodScanValues> extractor) {
    }

    private record ClassDimensionSpec(String label, ToIntFunction<RiskProfile.ClassScanValues> extractor) {
    }

    private static final Map<String, MethodDimensionSpec> METHOD_SPECS = Map.ofEntries(
            Map.entry("branching", new MethodDimensionSpec("Cyclomatic complexity", MethodScanValues::cyclomaticComplexity)),
            Map.entry("length", new MethodDimensionSpec("Lines of code", MethodScanValues::codeLines)),
            Map.entry("nesting", new MethodDimensionSpec("Nesting depth", MethodScanValues::maxNestingDepth)),
            Map.entry("parameters", new MethodDimensionSpec("Parameter count", MethodScanValues::parameterCount)),
            Map.entry("cognitive", new MethodDimensionSpec("Cognitive complexity", MethodScanValues::cognitiveComplexity)),
            Map.entry("logicalStatements", new MethodDimensionSpec("Logical statements", MethodScanValues::logicalStatements)),
            Map.entry("outboundDistinctCalls",
                    new MethodDimensionSpec("Outbound call diversity", MethodScanValues::outboundDistinctCalls)),
            Map.entry("exitPoints", new MethodDimensionSpec("Exit points", MethodScanValues::exitPoints)),
            Map.entry("lambdaCount", new MethodDimensionSpec("Lambda count", MethodScanValues::lambdaCount)),
            Map.entry("switchCases", new MethodDimensionSpec("Switch cases", MethodScanValues::switchCases)),
            Map.entry("maxTryNestingDepth", new MethodDimensionSpec("Try nesting depth", MethodScanValues::maxTryNestingDepth)),
            Map.entry("localVariableCount", new MethodDimensionSpec("Local variables", MethodScanValues::localVariableCount)),
            Map.entry("maxMethodCallChainLength",
                    new MethodDimensionSpec("Call chain length", MethodScanValues::maxMethodCallChainLength)),
            Map.entry("catchClauses", new MethodDimensionSpec("Catch clauses", MethodScanValues::catchClauses)),
            Map.entry("emptyCatchBlocks", new MethodDimensionSpec("Empty catch blocks", MethodScanValues::emptyCatchBlocks)),
            Map.entry("catchExceptionOrThrowable",
                    new MethodDimensionSpec("Broad catch (Exception/Throwable)", MethodScanValues::catchExceptionOrThrowable)),
            Map.entry("catchWithOnlyPrintStackTrace",
                    new MethodDimensionSpec("Catch with only printStackTrace", MethodScanValues::catchWithOnlyPrintStackTrace)),
            Map.entry("primitiveObsessionIndex",
                    new MethodDimensionSpec("Primitive obsession index", MethodScanValues::primitiveObsessionIndex)),
            Map.entry("maxBooleanOperatorsInCondition",
                    new MethodDimensionSpec("Complex conditional (bool ops peak)",
                            MethodScanValues::maxBooleanOperatorsInCondition)),
            Map.entry("halsteadDifficulty",
                    new MethodDimensionSpec("Halstead difficulty", MethodScanValues::halsteadDifficultyRounded)),
            Map.entry("halsteadEffort",
                    new MethodDimensionSpec("Halstead effort", MethodScanValues::halsteadEffortRounded)),
            Map.entry("rawTypeUsage",
                    new MethodDimensionSpec("Raw type usage", MethodScanValues::rawTypeUsage)),
            Map.entry("stringConcatInLoop",
                    new MethodDimensionSpec("String concat in loop", MethodScanValues::stringConcatInLoop)),
            Map.entry("hardcodedLiteralCount",
                    new MethodDimensionSpec("Hardcoded literal", MethodScanValues::hardcodedLiteralCount)),
            Map.entry("swallowedExceptionSmells",
                    new MethodDimensionSpec("Swallowed exception", MethodScanValues::swallowedExceptionSmells)),
            Map.entry("genericExceptionSmells",
                    new MethodDimensionSpec("Generic exception catch", MethodScanValues::genericExceptionSmells)));

    private static final Map<String, ClassDimensionSpec> CLASS_SPECS = Map.ofEntries(
            Map.entry("publicMethodCount",
                    new ClassDimensionSpec("Public method count", RiskProfile.ClassScanValues::publicMethodCount)),
            Map.entry("efferentCouplingProxy",
                    new ClassDimensionSpec("Efferent coupling (proxy)", RiskProfile.ClassScanValues::efferentCouplingProxy)),
            Map.entry("efferentTypeCount",
                    new ClassDimensionSpec("Efferent type count (Ce)", RiskProfile.ClassScanValues::efferentTypeCount)),
            Map.entry("lcom3Index",
                    new ClassDimensionSpec("LCOM3 cohesion index", RiskProfile.ClassScanValues::lcom3Times100)),
            Map.entry("halsteadEffortClass",
                    new ClassDimensionSpec("Class Halstead effort", RiskProfile.ClassScanValues::halsteadEffortRounded)),
            Map.entry("godClassIndicator",
                    new ClassDimensionSpec("God class candidate", RiskProfile.ClassScanValues::godClassIndicator)));

    /** CLI wiring: default profile when name blank, else load YAML. */
    public static RiskProfile loadDefaultProfile() {
        Path resolved = defaultConfigPath();
        try {
            return load(DEFAULT_PROFILE_ID, resolved);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to load default risk profile '" + DEFAULT_PROFILE_ID + "': " + e.getMessage(), e);
        }
    }

    public static RiskCalculator createCalculator(String profileName, Path configPath) {
        String resolvedName = profileName == null || profileName.isBlank()
                ? DEFAULT_PROFILE_ID
                : profileName.trim();
        Path resolved = configPath != null ? configPath : defaultConfigPath();
        try {
            RiskProfile profile = load(resolvedName, resolved);
            return new RiskCalculator(profile);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to load risk profile '" + resolvedName + "': " + e.getMessage(), e);
        }
    }
}
