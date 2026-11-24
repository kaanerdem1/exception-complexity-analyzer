-- Kalite düşüşünün açıklaması. risk_inputs_json karmaşıklık sayımlarını tutar.
-- Bu kolon kalitenin neden o puanda olduğunu tutar. Bir kez çalıştırılır.
--
-- Tarayıcı kolon varsa quality_inputs_json yazar ve kopyada taşır.
-- risk_inputs_json içindeki qualityScore durur. Üretilen kolonlara INSERT yazılmaz.
-- Eski satır '{}' kalır; dosya yeniden ölçülünce dolar.

ALTER TABLE env.method_risk_analyze
    ADD COLUMN IF NOT EXISTS quality_inputs_json jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE env.method_risk_analyze
    ADD COLUMN IF NOT EXISTS quality_critical_count numeric
        GENERATED ALWAYS AS ((quality_inputs_json ->> 'critical')::numeric) STORED;

ALTER TABLE env.method_risk_analyze
    ADD COLUMN IF NOT EXISTS quality_high_count numeric
        GENERATED ALWAYS AS ((quality_inputs_json ->> 'high')::numeric) STORED;

ALTER TABLE env.method_risk_analyze
    ADD COLUMN IF NOT EXISTS quality_medium_count numeric
        GENERATED ALWAYS AS ((quality_inputs_json ->> 'medium')::numeric) STORED;

ALTER TABLE env.method_risk_analyze
    ADD COLUMN IF NOT EXISTS quality_style_count numeric
        GENERATED ALWAYS AS ((quality_inputs_json ->> 'style')::numeric) STORED;

COMMENT ON COLUMN env.method_risk_analyze.quality_inputs_json IS
    'Kalite düşüşü. score, why, defectDrop, styleDrop, rawDrop, ceiling, critical, high, medium, style ve rules. Boş {} ölçülmemiş satırdır.';

COMMENT ON COLUMN env.method_risk_analyze.quality_critical_count IS
    'Kritik kalite kuralı adedi. İlki 15 puan düşer. quality_inputs_json.critical.';

COMMENT ON COLUMN env.method_risk_analyze.quality_high_count IS
    'Yüksek kalite kuralı adedi. İlki 10 puan düşer. quality_inputs_json.high.';

COMMENT ON COLUMN env.method_risk_analyze.quality_medium_count IS
    'Orta kalite kuralı adedi. İlki 6 puan düşer. quality_inputs_json.medium.';

COMMENT ON COLUMN env.method_risk_analyze.quality_style_count IS
    'Stil kuralı adedi. Basamak 15, 10, 8, 7, 3, 2, sonrası 1. quality_inputs_json.style.';
