package com.scanner.complexity;

/** One exception-handling anti-pattern occurrence found in a {@code catch} clause. */
public record ExceptionSmell(Type type, String exceptionType, int line) {

    public enum Type {
        /** Empty catch block, or a body that only calls printStackTrace()/System.out|err print. */
        SWALLOWED_EXCEPTION,
        /** {@code catch (Exception e)} or {@code catch (Throwable t)}. */
        GENERIC_EXCEPTION_CATCH
    }
}
