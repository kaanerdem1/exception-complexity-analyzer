package com.scanner.exception.db;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Sınıf ve metot envanterinin okunduğu kaynak (PostgreSQL veya testte bellek). */
interface DbCatalog {

    /** Aranan FQCN'lere karşılık gelen sınıf kayıtları; anahtar aranan FQCN'dir. */
    Map<String, List<ClassRow>> findClasses(Collection<String> fqcns) throws Exception;

    /** Verilen sınıfların tüm metot kayıtları; anahtar class_id'dir. */
    Map<Long, List<MethodRow>> findMethods(Collection<Long> classIds) throws Exception;

    final class ClassRow {
        final long id;
        final String fqcn;

        ClassRow(long id, String fqcn) {
            this.id = id;
            this.fqcn = fqcn;
        }
    }

    final class MethodRow {
        final long id;
        final long classId;
        /** Tablodaki ad; "getRate", "getRate(String a, String b)" veya yarım "getRate(String a," olabilir */
        final String name;
        /** Opsiyonel imza sütunu; yoksa null */
        final String signature;

        MethodRow(long id, long classId, String name, String signature) {
            this.id = id;
            this.classId = classId;
            this.name = name;
            this.signature = signature;
        }
    }
}
