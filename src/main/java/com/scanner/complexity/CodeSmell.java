package com.scanner.complexity;

/** One Java-6-era code smell occurrence (raw type, string concatenation in a loop, hardcoded literal). */
public record CodeSmell(Type type, int line, String detail) {

    public enum Type {
        /** A generic-capable JDK type (List, Map, ...) used without type arguments. */
        RAW_TYPE,
        /** {@code x += ...} or {@code x = x + ...} on a String-typed variable inside a loop. */
        STRING_CONCAT_IN_LOOP,
        /** String literal that looks like a hardcoded IPv4 address. */
        HARDCODED_IP,
        /** String literal that looks like a hardcoded SQL statement. */
        HARDCODED_SQL,
        /** String literal that looks like a hardcoded URL / JDBC connection string. */
        HARDCODED_URL
    }
}
