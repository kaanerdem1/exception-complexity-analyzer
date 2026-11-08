-- =====================================================================
--  Kod karmaşıklığı tabloları (env.method_risk_analyze, env.method_similarity)
--  için geçiş: scan_run_id'yi env.exception_scan_run.id'ye (bigint) bağlar.
--  Tablolar envanter tarafında oluşturulmuştur; bu dosya bir kez çalıştırılır.
--
--  Not: tablolarda proje / repo sütunu olmadığı için aynı taramadaki farklı
--  repoların satırları file_path ile ayırt edilemez. İleride gerekirse
--  project_key varchar(100) ve repo varchar(200) sütunları eklenebilir;
--  tarayıcı bu sütunlar varsa onları da doldurur.
-- =====================================================================

-- scan_run_id: uuid -> env.exception_scan_run.id (bigint). Tablolarda veri varsa uuid değerleri taşınamaz;
-- bu komutlar tabloyu boşaltır (TRUNCATE). Veri saklanacaksa önce yedekleyin.
TRUNCATE env.method_risk_analyze, env.method_similarity;

ALTER TABLE env.method_risk_analyze
    ALTER COLUMN scan_run_id TYPE bigint USING NULL,
    ADD CONSTRAINT fk_method_risk_analyze_run FOREIGN KEY (scan_run_id)
        REFERENCES env.exception_scan_run (id) ON DELETE CASCADE;

ALTER TABLE env.method_similarity
    ALTER COLUMN scan_run_id TYPE bigint USING NULL,
    ADD CONSTRAINT fk_method_similarity_run FOREIGN KEY (scan_run_id)
        REFERENCES env.exception_scan_run (id) ON DELETE CASCADE;

CREATE INDEX ix_method_risk_analyze_run  ON env.method_risk_analyze (scan_run_id);
CREATE INDEX ix_method_similarity_run    ON env.method_similarity (scan_run_id);
CREATE INDEX ix_method_risk_analyze_java ON env.method_risk_analyze (java_method_id);

COMMENT ON COLUMN env.method_risk_analyze.scan_run_id IS 'Kaydın ait olduğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.method_similarity.scan_run_id IS 'Kaydın ait olduğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
