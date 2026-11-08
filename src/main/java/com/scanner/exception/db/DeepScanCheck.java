package com.scanner.exception.db;

import com.scanner.exception.config.Config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Otomatik taramanın ön koşulu: deep-scanner son db.deep.scan.max.age.days gün içinde başarıyla
 * bitmiş olmalı (env.scan_run'da status=SUCCESS ve finished_at bu süre içinde olan bir kayıt).
 * Envanter güncel değilken yapılan tarama eski sınıf/metot id'leriyle eşleşeceği için pas geçilir.
 */
public final class DeepScanCheck {

    /** Kontrolün sonucu; lastSuccess hiç başarılı kayıt yoksa null */
    public static final class Result {
        public final boolean fresh;
        public final Timestamp lastSuccess;

        Result(boolean fresh, Timestamp lastSuccess) {
            this.fresh = fresh;
            this.lastSuccess = lastSuccess;
        }
    }

    private DeepScanCheck() {
    }

    public static Result check(Config.Db db) throws SQLException {
        // Süre veritabanının saatiyle hesaplanır: finished_at da onun saatiyle yazılıyor
        String sql = "SELECT max(" + db.deepScanFinishedColumn + "),"
                + " coalesce(max(" + db.deepScanFinishedColumn + ") > now() - ? * interval '1 day', false)"
                + " FROM " + db.deepScanTable
                + " WHERE " + db.deepScanStatusColumn + " = ?";
        try (Connection con = PostgresExporter.connect(db);
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, db.deepScanMaxAgeDays);
            ps.setString(2, db.deepScanSuccessStatus);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Result(rs.getBoolean(2), rs.getTimestamp(1));
            }
        }
    }
}
