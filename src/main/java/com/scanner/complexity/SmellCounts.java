package com.scanner.complexity;

import java.util.List;

/** Counts legacy smell lists for risk dimensions. */
final class SmellCounts {

    private SmellCounts() {
    }

    static int swallowed(List<ExceptionSmell> smells) {
        return (int) smells.stream().filter(e -> e.type() == ExceptionSmell.Type.SWALLOWED_EXCEPTION).count();
    }

    static int genericCatch(List<ExceptionSmell> smells) {
        return (int) smells.stream().filter(e -> e.type() == ExceptionSmell.Type.GENERIC_EXCEPTION_CATCH).count();
    }

    static int rawType(List<CodeSmell> smells) {
        return (int) smells.stream().filter(c -> c.type() == CodeSmell.Type.RAW_TYPE).count();
    }

    static int concatInLoop(List<CodeSmell> smells) {
        return (int) smells.stream().filter(c -> c.type() == CodeSmell.Type.STRING_CONCAT_IN_LOOP).count();
    }

    static int hardcoded(List<CodeSmell> smells) {
        return (int) smells.stream().filter(c -> c.type() == CodeSmell.Type.HARDCODED_IP
                || c.type() == CodeSmell.Type.HARDCODED_SQL
                || c.type() == CodeSmell.Type.HARDCODED_URL).count();
    }
}
