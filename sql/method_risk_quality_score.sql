-- Kalite skoru (0–100, yüksek = iyi). risk_score karmaşıklıktır (yüksek = kötü).
-- İki skor birleştirilmez. Bir kez çalıştırılır.
ALTER TABLE env.method_risk_analyze
    ADD COLUMN IF NOT EXISTS quality_score numeric(5, 2);

COMMENT ON COLUMN env.method_risk_analyze.quality_score IS
    'Kalite skoru 0-100, yüksek = iyi. risk_score karmaşıklıktır, yüksek = kötü.';
