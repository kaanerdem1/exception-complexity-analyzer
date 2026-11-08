package com.scanner.exception.model;

/** Hatayı yutan bir catch bloğu (env.swallowed_exceptions). */
public final class CatchFinding extends Located {
    public String caughtTypes = "";
    /** Hatayı nasıl yuttuğu (env.swallowed_exceptions.catch_kind) */
    public SwallowKind swallowKind;

    public enum SwallowKind {
        /** Gövde boş veya sadece yorum */
        EMPTY,
        /** Gövdede sadece log çağrıları var */
        LOG_ONLY,
        /** Log çağrıları ve ardından hatayı taşımayan return (ör. return null) */
        LOG_AND_RETURN,
        /** Log olmadan sadece hatayı taşımayan return: return null, return false, return "", return EMPTY, return; */
        RETURN_ONLY
    }

    public String note() {
        switch (swallowKind) {
            case EMPTY: return "Exception yutuluyor, hata kayboluyor";
            case LOG_ONLY: return "Sadece loglanıp yutuluyor, hata çağırana ulaşmıyor";
            case LOG_AND_RETURN: return "Loglanıp return ediliyor, hata çağırana ulaşmıyor";
            default: return "Loglanmadan değer dönülüyor, hata kayboluyor";
        }
    }
}
