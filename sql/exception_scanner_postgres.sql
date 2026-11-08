-- =====================================================================
--  Code Scanner veritabanı nesneleri (PostgreSQL 9.6+), ilk kurulum
--
--  Exception kullanımları, tarama hataları ve ekran / popup / region /
--  Jasper rapor / process envanteri. Hepsi env.exception_scan_run
--  tablosundaki tarama kaydına bağlıdır.
--
--  Mevcut tablolar (değiştirilmez, sadece okunur):
--    env.java_class  (id, fqcn, ...)              FK ile bağlanmaz
--    env.java_method (id, class_id, name, ...)    FK ile bağlanmaz
--    env.project     (id, project_name, ...)      envanter tabloları FK ile bağlanır
--
--  Sütun adları sizde farklıysa REFERENCES env.project satırlarını ve
--  scanner.properties içindeki db.* ayarlarını birlikte güncelleyin.
-- =====================================================================

-- ---------------------------------------------------------------------
--  Kullanım tipleri: tarayıcıdaki desenlerin karşılığı
--  (pattern.N.type). Tarayıcı her çalışmada bu tabloyu
--  ayar dosyasındaki desenlerle günceller; elle de kayıt eklenebilir.
-- ---------------------------------------------------------------------
CREATE TABLE env.exception_usage_type (
    type_code     varchar(50)  PRIMARY KEY,           -- KOD0_SERBEST_METIN
    type_name     varchar(200) NOT NULL,              -- "Kod 0 + serbest metin"
    action        varchar(200),                       -- "Yeni yapıya çevrilmeli"
    args_spec     varchar(500),                       -- "0,STRING,*"
    source_kind   varchar(20)  NOT NULL DEFAULT 'EXCEPTION',  -- EXCEPTION
    is_legacy     boolean      NOT NULL DEFAULT true,  -- false: doğru kullanım (takip amaçlı sayılır)
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  env.exception_usage_type IS 'Kullanım tipleri: ayar dosyasındaki her desenin (pattern.N.*) karşılığı. Tarayıcı her çalıştırmada desenleri bu tabloya ekler veya günceller (silmez); elle de kayıt eklenebilir.';
COMMENT ON COLUMN env.exception_usage_type.type_code IS 'Kullanım tipi kodu: pattern.N.type, verilmediyse desen adından üretilir (ör. KOD0_SERBEST_METIN); büyük harf, rakam ve _ (en fazla 50). pattern.N tanımlanmadan exception sınıfı arandığında tüm kullanımlar ALL_USAGES koduyla yazılır';
COMMENT ON COLUMN env.exception_usage_type.type_name IS 'Desenin adı (pattern.N.name), ör. "Kod 0 + serbest metin"; ad verilmediyse "Desen N (...)", ALL_USAGES için "Tüm kullanımlar"';
COMMENT ON COLUMN env.exception_usage_type.action IS 'Bu tipteki kullanımlar için yapılacak iş (pattern.N.action); verilmediyse "Yeni yapıya çevrilmeli", ALL_USAGES için "İncelenmeli". Sadece elle eklenen kayıtlarda boş olabilir';
COMMENT ON COLUMN env.exception_usage_type.args_spec IS 'Desenin argüman tanımı: pattern.N.args (ör. 0,STRING,*); ALL_USAGES için *';
COMMENT ON COLUMN env.exception_usage_type.source_kind IS 'Desenin türü: tarayıcı EXCEPTION yazar (new Sinif(...) ile exception oluşturma, pattern.N)';
COMMENT ON COLUMN env.exception_usage_type.is_legacy IS 'true (varsayılan): eski, değiştirilmesi gereken kullanım; false: doğru kullanım, sadece takip için sayılır (pattern.N.legacy=false)';
COMMENT ON COLUMN env.exception_usage_type.updated_at IS 'Kaydın tarayıcı tarafından son eklendiği veya güncellendiği an (desen ayar dosyasında oldukça her çalıştırmada yenilenir)';

-- ---------------------------------------------------------------------
--  Tarama çalıştırmaları: her çalıştırma bir kayıt. Kullanımlar bu
--  kayda bağlı tutulur; böylece aynı tarama iki kez çalışınca veri
--  çoğalmaz, haftadan haftaya ilerleme de karşılaştırılabilir.
-- ---------------------------------------------------------------------
CREATE TABLE env.exception_scan_run (
    id                 bigserial    PRIMARY KEY,
    started_at         timestamptz  NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    source             varchar(20),                    -- SERVER | CLOUD | GIT | LOCAL
    repo_count         integer,
    java_file_count    integer,
    usage_count        integer,
    swallowed_error_count integer,                       -- env.swallowed_exceptions'a yazılan hata yutan catch bloğu sayısı
    exception_classes  text,
    note               text,                           -- --db-note ile verilen açıklama, yoksa "<tarih> tarihli tarama"
    run_by             varchar(100),
    host               varchar(200),
    screen_count       integer,                        -- ebml.enabled=true ile yapılan taramalarda dolu
    popup_count        integer,
    region_count       integer,
    report_count       integer,
    process_count      integer
);

COMMENT ON TABLE  env.exception_scan_run IS 'Tarayıcının her çalıştırması için bir kayıt. Kullanımlar, tarama hataları ve ekran/popup/region/rapor/process envanteri bu kayda bağlıdır.';
COMMENT ON COLUMN env.exception_scan_run.id IS 'Tarama numarası; diğer tablolardaki scan_run_id bu değeri gösterir';
COMMENT ON COLUMN env.exception_scan_run.started_at IS 'Taramanın başladığı an (repoların listelenmeye başlandığı an)';
COMMENT ON COLUMN env.exception_scan_run.finished_at IS 'Taramanın sonuçları veritabanına yazılıp bittiği an; görünümler sadece dolu olan (bitmiş) taramaları kullanır';
COMMENT ON COLUMN env.exception_scan_run.source IS 'Repoların alındığı kaynak: SERVER (Bitbucket Server/Data Center), CLOUD (Bitbucket Cloud), GIT (git adresi) veya LOCAL (yerel klasör)';
COMMENT ON COLUMN env.exception_scan_run.repo_count IS 'Taramaya alınan repo sayısı: bitbucket.exclude.repos ile hariç tutulanlar sayılmaz, klonlanamayanlar (CLONE hatası) sayılır';
COMMENT ON COLUMN env.exception_scan_run.java_file_count IS 'Repolarda bulunan .java dosyası sayısı (scan.max.file.kb sınırı nedeniyle atlananlar dahil); sınıf aranmadıysa (sadece envanter) 0';
COMMENT ON COLUMN env.exception_scan_run.usage_count IS 'Bu taramada env.exception_usage tablosuna yazılan kullanım sayısı';
COMMENT ON COLUMN env.exception_scan_run.swallowed_error_count IS 'Bu taramada env.swallowed_exceptions tablosuna yazılan hata yutan catch bloğu sayısı (kapsamı scan.swallowed.scope); db.insert.unmatched=false ile atlananlar sayılmaz. env.swallowed_exceptions tablosu yoksa veya scan.swallowed.scope=OFF ise boş';
COMMENT ON COLUMN env.exception_scan_run.exception_classes IS 'Aranan exception sınıflarının tam adları (exception.classes), virgülle ayrılmış; sadece envanter çıkarıldıysa (sınıf aranmadıysa) boş metin';
COMMENT ON COLUMN env.exception_scan_run.note IS '--db-note parametresi veya db.note ayarıyla verilen açıklama; verilmediyse taramanın başladığı an "21.10.2026 11:50:34 tarihli tarama" biçiminde yazılır (saat dilimi db.note.zone, varsayılan Europe/Istanbul)';
COMMENT ON COLUMN env.exception_scan_run.run_by IS 'Taramayı çalıştıran işletim sistemi kullanıcısı';
COMMENT ON COLUMN env.exception_scan_run.host IS 'Taramanın çalıştığı makinenin adı';
COMMENT ON COLUMN env.exception_scan_run.screen_count IS 'Bu taramada env.all_screens tablosuna yazılan ekran sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş. env.v_ebml_inventory_latest son envanter taramasını bu sütunun dolu olmasından bulur';
COMMENT ON COLUMN env.exception_scan_run.popup_count IS 'Bu taramada env.all_popups tablosuna yazılan popup sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş';
COMMENT ON COLUMN env.exception_scan_run.region_count IS 'Bu taramada env.all_regions tablosuna yazılan region sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş';
COMMENT ON COLUMN env.exception_scan_run.report_count IS 'Bu taramada env.all_reports tablosuna yazılan Jasper rapor sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş';
COMMENT ON COLUMN env.exception_scan_run.process_count IS 'Bu taramada env.all_processes tablosuna yazılan process sayısı; tarama ebml.enabled=true (--ebml) olmadan çalıştıysa boş';


-- ---------------------------------------------------------------------
--  Exception kullanımları
-- ---------------------------------------------------------------------
CREATE TABLE env.exception_usage (
    id               bigserial    PRIMARY KEY,
    scan_run_id      bigint       NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    -- Tarama anındaki env.java_class / env.java_method id'leri. FK yok: o tablolar her
    -- yüklemede TRUNCATE + INSERT ile yenilendiği için FK yüklemeyi engeller ve id'ler
    -- sonraki yüklemede değişebilir. Kalıcı eşleştirme için class_fqcn ve method_signature
    -- sütunlarındaki metin kullanılmalı (bkz. aşağıdaki örnek sorgu).
    class_id         bigint,
    method_id        bigint,
    usage_type_code  varchar(50)  NOT NULL REFERENCES env.exception_usage_type (type_code),

    -- Eşleştirme sonucu:
    --   MATCHED           sınıf ve metot bulundu
    --   METHOD_AMBIGUOUS  aynı adda birden fazla metot, en küçük id seçildi
    --   METHOD_NOT_FOUND  sınıf bulundu, metot bulunamadı (method_id boş)
    --   NO_METHOD         kullanım bir alan tanımında, metot yok (method_id boş)
    --   CLASS_AMBIGUOUS   aynı FQCN birden fazla kayıtta ve metot ayırt edemedi, en küçük id seçildi
    --   CLASS_NOT_FOUND   sınıf bulunamadı (class_id ve method_id boş)
    match_status     varchar(30)  NOT NULL,
    match_note       varchar(1000),

    project_key      varchar(100),
    repo             varchar(200),
    module           varchar(300),
    file_path        varchar(1000) NOT NULL,
    line_no          integer      NOT NULL,
    class_fqcn       varchar(1000) NOT NULL,           -- kodda bulunan sınıf (anonim sınıf dahil)
    method_signature varchar(1000),                    -- kodda bulunan metot: getRate(String, String)
    exception_class  varchar(300),                     -- CSException veya alt sınıfı
    error_code       integer,                          -- new CSException(0, ...) -> 0
    message          text,                             -- koddaki mesaj / şablon
    usage_context    varchar(50),                      -- THROW | RETURN | ASSIGNMENT | ARGUMENT | OTHER
    is_test          boolean      NOT NULL DEFAULT false,
    code_snippet     text,
    link             varchar(2000),
    created_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_exception_usage_method_needs_class CHECK (method_id IS NULL OR class_id IS NOT NULL)
);

COMMENT ON TABLE  env.exception_usage IS 'Exception kullanımları: desenlere uyan her exception oluşturma (new ...) yeri için bir kayıt, bulunduğu sınıf ve metodun envanterdeki id''leriyle. Her kayıt bir taramaya bağlıdır; son tarama için env.v_exception_usage_latest kullanılır.';
COMMENT ON COLUMN env.exception_usage.id IS 'Kayıt numarası (otomatik artan)';
COMMENT ON COLUMN env.exception_usage.scan_run_id IS 'Kaydın ait olduğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.exception_usage.class_id IS 'Tarama anında kullanımın bulunduğu sınıfın env.java_class id''si (db.class.*); CLASS_NOT_FOUND ise boş. FK yok, envanter yeniden yüklenince değişebilir: kalıcı eşleştirme için class_fqcn kullanılmalı';
COMMENT ON COLUMN env.exception_usage.method_id IS 'Tarama anında kullanımın bulunduğu metodun env.java_method id''si (db.method.*); metot bulunamadıysa (METHOD_NOT_FOUND, NO_METHOD, CLASS_NOT_FOUND veya metodu bulunamayan CLASS_AMBIGUOUS) boş. FK yok; class_id boşken dolu olamaz';
COMMENT ON COLUMN env.exception_usage.usage_type_code IS 'Kullanımın uyduğu desenin tip kodu, env.exception_usage_type.type_code değerine bağlı (FK); ör. KOD0_SERBEST_METIN, pattern.N tanımlanmadıysa ALL_USAGES';
COMMENT ON COLUMN env.exception_usage.match_status IS 'Envanter eşleştirmesinin sonucu: MATCHED (sınıf ve metot bulundu), METHOD_AMBIGUOUS (aynı adda birden fazla metot, en küçük id seçildi), METHOD_NOT_FOUND (sınıf bulundu, metot bulunamadı), NO_METHOD (kullanım alan tanımında veya metot dışında), CLASS_AMBIGUOUS (aynı FQCN birden fazla kayıtta, en küçük id seçildi), CLASS_NOT_FOUND (sınıf envanterde yok; db.insert.unmatched=false ise bu kullanımlar yazılmaz)';
COMMENT ON COLUMN env.exception_usage.match_note IS 'Eşleştirme sonucunun açıklaması, ör. CLASS_NOT_FOUND için denenen adlar ("Aranan: a.b.Dis$Ic, a.b.Dis.Ic, a.b.Dis"), overload seçimi, dış sınıfa veya anonim sınıfı içeren sınıfa düşülmesi; açıklama yoksa boş';
COMMENT ON COLUMN env.exception_usage.project_key IS 'Reponun projesi: Bitbucket proje anahtarı; source=GIT için adresteki sahip (owner, bulunamazsa GIT), source=LOCAL için klasör yapısından çıkarılan proje adı';
COMMENT ON COLUMN env.exception_usage.repo IS 'Repo slug''ı (Bitbucket/git adresindeki repo adı; source=LOCAL için repo klasörünün adı)';
COMMENT ON COLUMN env.exception_usage.module IS 'Dosyanın modülü: pom.xml, build.gradle, build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu; repo kökündeyse (kök)';
COMMENT ON COLUMN env.exception_usage.file_path IS 'Kullanımın bulunduğu .java dosyasının repo içindeki yolu, / ayraçlı (ör. src/main/java/a/b/Dis.java)';
COMMENT ON COLUMN env.exception_usage.line_no IS 'Kullanımın (new ... ifadesinin) başladığı satır; bilinmiyorsa 0';
COMMENT ON COLUMN env.exception_usage.class_fqcn IS 'Kullanımın kodda bulunduğu sınıfın paketle birlikte tam adı; iç sınıflar nokta ile, anonim sınıflar <anonim Tip> olarak yazılır (ör. a.b.Dis.Ic.<anonim Runnable>). Envanterle kalıcı eşleştirme bu sütunla yapılmalı';
COMMENT ON COLUMN env.exception_usage.method_signature IS 'Kullanımın kodda bulunduğu üye: metot imzası (ör. getRate(String, String)), constructor için "Ad(String) [constructor]", bloklarda <static blok> / <instance blok>, alan tanımında <alan: ad>; üye yoksa boş';
COMMENT ON COLUMN env.exception_usage.exception_class IS 'Oluşturulan exception sınıfının basit adı (exception.classes''taki sınıf veya repoda ondan türeyen alt sınıf, ör. CSException)';
COMMENT ON COLUMN env.exception_usage.error_code IS 'Argümanlardaki ilk tam sayı sabiti (new CSException(0, ...) -> 0); sayı argümanı yoksa veya int sınırını aşıyorsa boş';
COMMENT ON COLUMN env.exception_usage.message IS 'Koddaki mesaj: ilk string argüman; birleştirmelerde değişkenler {0}, {1} ile gösterilir ("Kayıt bulunamadı: " + id -> Kayıt bulunamadı: {0}), String.format / MessageFormat.format çağrılarında biçim metni. Mesaj argümanı yoksa boş metin';
COMMENT ON COLUMN env.exception_usage.usage_context IS 'Kullanımın bağlamı: THROW (throw new ...), RETURN (return new ...), ASSIGNMENT (değişkene atama), ARGUMENT (bir çağrıya parametre olarak) veya OTHER (diğer)';
COMMENT ON COLUMN env.exception_usage.is_test IS 'Dosya test kodunda mı: yolunda src/test, test veya tests klasörü varsa true';
COMMENT ON COLUMN env.exception_usage.code_snippet IS 'Kullanımın kaynak kodu (new ... ifadesi), boşluklar teke indirilmiş; 500 karakterden uzunsa kesilip sonuna ... eklenir';
COMMENT ON COLUMN env.exception_usage.link IS 'Dosyayı ilgili satırda açan Bitbucket (source=GIT ise GitHub/GitLab) bağlantısı, taranan commit''e sabitlenmiş; source=LOCAL veya bağlantı üretilemeyen git adreslerinde boş';
COMMENT ON COLUMN env.exception_usage.created_at IS 'Kaydın eklendiği an (now()); tarama tek transaction''da yazıldığı için transaction''ın başladığı an olur ve aynı taramanın tüm kayıtlarında aynıdır';

CREATE INDEX ix_exception_usage_run    ON env.exception_usage (scan_run_id);
CREATE INDEX ix_exception_usage_class  ON env.exception_usage (class_id);
CREATE INDEX ix_exception_usage_method ON env.exception_usage (method_id);
CREATE INDEX ix_exception_usage_type   ON env.exception_usage (usage_type_code);

-- ---------------------------------------------------------------------
--  Tarama sırasında karşılaşılan hatalar (Excel'deki "Tarama Hataları"
--  sayfasının karşılığı). Kullanımlarla aynı transaction'da yazılır.
--  stage:
--    CLONE          repo klonlanamadı / güncellenemedi (repo hiç taranmadı)
--    BRANCH_FALLBACK  git.branch'teki dal repoda yok, varsayılan dal tarandı (hata değil, uyarı)
--    SCAN           repo taranırken beklenmeyen hata
--    OUT_OF_MEMORY  repo için bellek yetmedi
--    FILE_LISTING   repodaki dosyalar listelenemedi
--    EBML_LISTING   EBML / rapor / process dosyaları listelenemedi
--    PARSE          Java dosyası ayrıştırılamadı (kısmen taranmış olabilir)
--    READ           dosya okunamadı
--    SKIPPED        dosya scan.max.file.kb sınırından büyük, taranmadı
--    PROCESS_LABEL  processdefinition.xml okunamadı veya label özelliği yok
-- ---------------------------------------------------------------------
CREATE TABLE env.exception_scan_error (
    id           bigserial     PRIMARY KEY,
    scan_run_id  bigint        NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_key  varchar(100),
    repo         varchar(200),
    stage        varchar(30)   NOT NULL,
    file_path    varchar(1000),                        -- dosya bazlı hatalarda repo içindeki yol
    message      text,
    created_at   timestamptz   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  env.exception_scan_error IS 'Tarama sırasında karşılaşılan hatalar (Excel''deki "Tarama Hataları" sayfasının karşılığı): klonlanamayan repo, ayrıştırılamayan dosya vb. Kullanımlarla aynı transaction''da yazılır; son tarama için env.v_exception_scan_error_latest kullanılır.';
COMMENT ON COLUMN env.exception_scan_error.id IS 'Kayıt numarası (otomatik artan)';
COMMENT ON COLUMN env.exception_scan_error.scan_run_id IS 'Hatanın oluştuğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.exception_scan_error.project_key IS 'Hatanın oluştuğu reponun projesi: Bitbucket proje anahtarı; source=GIT için adresteki sahip (owner, bulunamazsa GIT), source=LOCAL için klasör yapısından çıkarılan proje adı';
COMMENT ON COLUMN env.exception_scan_error.repo IS 'Hatanın oluştuğu reponun slug''ı (source=LOCAL için repo klasörünün adı)';
COMMENT ON COLUMN env.exception_scan_error.stage IS 'Hatanın türü: CLONE (repo klonlanamadı / güncellenemedi, repo hiç taranmadı), BRANCH_FALLBACK (git.branch''te yazan dal repoda yok, git.branch.fallback=true olduğu için varsayılan dal tarandı; repo taranmıştır, bu bir uyarıdır), SCAN (repo taranırken beklenmeyen hata), OUT_OF_MEMORY (repo için bellek yetmedi), FILE_LISTING (repodaki dosyalar listelenemedi), EBML_LISTING (EBML / rapor / process dosyaları listelenemedi), PARSE (Java dosyası ayrıştırılamadı, kısmen taranmış olabilir), READ (dosya okunamadı), SKIPPED (dosya scan.max.file.kb sınırından büyük, taranmadı), PROCESS_LABEL (processdefinition.xml okunamadı veya label özelliği yok)';
COMMENT ON COLUMN env.exception_scan_error.file_path IS 'Dosya bazlı hatalarda (PARSE, READ, SKIPPED, PROCESS_LABEL) dosyanın repo içindeki yolu; repo bazlı hatalarda (CLONE, SCAN, OUT_OF_MEMORY, FILE_LISTING, EBML_LISTING) boş';
COMMENT ON COLUMN env.exception_scan_error.message IS 'Hata mesajı: Java hatasının mesajı, ayrıştırma hatasının açıklaması (en fazla 400 karakter) veya tarayıcının açıklaması (ör. "Dosya çok büyük (3000 KB)..."); mesaj yoksa boş metin';
COMMENT ON COLUMN env.exception_scan_error.created_at IS 'Kaydın eklendiği an (now()); tarama tek transaction''da yazıldığı için transaction''ın başladığı an olur ve aynı taramanın tüm kayıtlarında aynıdır';

CREATE INDEX ix_exception_scan_error_run ON env.exception_scan_error (scan_run_id, stage);

-- =====================================================================
--  Ekran, region, Jasper rapor ve process envanteri (ebml.enabled=true)
-- =====================================================================

-- ---------------------------------------------------------------------
--  Ortak sütunlar (tüm envanter tablolarında aynı):
--    project_id    : env.project.id; proje bulunamazsa boş
--    project_name  : env.project tablosunda aranan ana proje adı
--    project_match : MATCHED | PROJECT_AMBIGUOUS (en küçük id seçildi) | PROJECT_NOT_FOUND
--    file_name     : dosya adı (ebml.file.name.with.extension=false ise uzantısız; env.all_processes'te yok,
--                    orada klasör adı folder_name sütunundadır)
--    file_path     : repo içindeki yol
--    link          : Bitbucket'ta dosyayı açan bağlantı (taranan commit'e sabitlenmiş)
--    created_at    : kaydın eklendiği tarih
-- ---------------------------------------------------------------------

-- Ekranlar: ebml.page paketi (ve alt paketleri) altındaki .ebml dosyaları
CREATE TABLE env.all_screens (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    package_name   varchar(1000),
    file_name      varchar(500)   NOT NULL,
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    created_at     timestamptz    NOT NULL DEFAULT now()
);
COMMENT ON TABLE  env.all_screens               IS 'Ekran envanteri: ebml.page paketi (ebml.page.package) ve alt paketleri altındaki .ebml dosyaları; ebml.enabled=true (--ebml) ile yapılan her taramada dosya başına bir kayıt. Adı RG_ ile başlayan dosyalar ekran değil region sayılır (env.all_regions).';
COMMENT ON COLUMN env.all_screens.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_screens.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_screens.project_id    IS 'Ekranın ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_screens.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE; modül repo köküyse repo adı) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_screens.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_screens.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_screens.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_screens.package_name  IS 'Dosyanın klasöründen üretilen paket adı: modül klasörü ve kaynak kökü (ebml.source.roots, ör. src/main/java, src/main/resources) atılıp / yerine . konur, ör. tr.com.x.ebml.page; dosya doğrudan kaynak kökündeyse boş';
COMMENT ON COLUMN env.all_screens.file_name     IS 'Dosya adı, ör. EKRAN001.ebml; ebml.file.name.with.extension=false ise uzantısız (EKRAN001)';
COMMENT ON COLUMN env.all_screens.file_path     IS 'Dosyanın repo içindeki yolu, ör. musteri-web/src/main/java/tr/com/x/ebml/page/EKRAN001.ebml';
COMMENT ON COLUMN env.all_screens.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_screens.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

-- Popup'lar: ebml.popup paketi (ve alt paketleri) altındaki .ebml dosyaları
CREATE TABLE env.all_popups (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    package_name   varchar(1000),
    file_name      varchar(500)   NOT NULL,
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    created_at     timestamptz    NOT NULL DEFAULT now()
);
COMMENT ON TABLE  env.all_popups               IS 'Popup envanteri: ebml.popup paketi (ebml.popup.package) ve alt paketleri altındaki .ebml dosyaları; ebml.enabled=true (--ebml) ile yapılan her taramada dosya başına bir kayıt. Adı RG_ ile başlayan dosyalar popup değil region sayılır (env.all_regions).';
COMMENT ON COLUMN env.all_popups.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_popups.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_popups.project_id    IS 'Popup''ın ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_popups.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE; modül repo köküyse repo adı) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_popups.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_popups.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_popups.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_popups.package_name  IS 'Dosyanın klasöründen üretilen paket adı: modül klasörü ve kaynak kökü (ebml.source.roots, ör. src/main/java, src/main/resources) atılıp / yerine . konur, ör. tr.com.x.ebml.popup; dosya doğrudan kaynak kökündeyse boş';
COMMENT ON COLUMN env.all_popups.file_name     IS 'Dosya adı, ör. pp_deneme.ebml; ebml.file.name.with.extension=false ise uzantısız (pp_deneme)';
COMMENT ON COLUMN env.all_popups.file_path     IS 'Dosyanın repo içindeki yolu, ör. musteri-web/src/main/java/tr/com/x/ebml/popup/pp_deneme.ebml';
COMMENT ON COLUMN env.all_popups.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_popups.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

-- Region'lar: adı RG_ ile başlayan veya ebml.region paketi altındaki .ebml dosyaları
CREATE TABLE env.all_regions (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    package_name   varchar(1000),
    file_name      varchar(500)   NOT NULL,
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    match_rule     varchar(20)    NOT NULL,            -- PREFIX | PACKAGE | PREFIX+PACKAGE
    created_at     timestamptz    NOT NULL DEFAULT now()
);
COMMENT ON TABLE  env.all_regions               IS 'Region envanteri: adı RG_ (ebml.region.prefix) ile başlayan veya ebml.region paketi (ebml.region.package) ve alt paketleri altındaki .ebml dosyaları; ebml.enabled=true (--ebml) ile yapılan her taramada dosya başına bir kayıt. Region kuralı ekran/popup kuralından önce uygulanır.';
COMMENT ON COLUMN env.all_regions.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_regions.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_regions.project_id    IS 'Region''ın ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_regions.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE; modül repo köküyse repo adı) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_regions.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_regions.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_regions.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_regions.package_name  IS 'Dosyanın klasöründen üretilen paket adı: modül klasörü ve kaynak kökü (ebml.source.roots, ör. src/main/java, src/main/resources) atılıp / yerine . konur, ör. tr.com.x.ebml.region; dosya doğrudan kaynak kökündeyse boş';
COMMENT ON COLUMN env.all_regions.file_name     IS 'Dosya adı, ör. RG_Adres.ebml; ebml.file.name.with.extension=false ise uzantısız (RG_Adres)';
COMMENT ON COLUMN env.all_regions.file_path     IS 'Dosyanın repo içindeki yolu, ör. musteri-web/src/main/java/tr/com/x/ebml/region/RG_Adres.ebml';
COMMENT ON COLUMN env.all_regions.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_regions.match_rule    IS 'Dosyanın region sayılma kuralı: PREFIX (adı ebml.region.prefix ile, varsayılan RG_, başlıyor; harf duyarsız), PACKAGE (ebml.region paketi veya alt paketleri altında) veya PREFIX+PACKAGE (ikisi birden)';
COMMENT ON COLUMN env.all_regions.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

-- Jasper raporları: ebml.report paketi (ve alt paketleri) altındaki .dsxml dosyaları
CREATE TABLE env.all_reports (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    package_name   varchar(1000),
    file_name      varchar(500)   NOT NULL,
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    created_at     timestamptz    NOT NULL DEFAULT now()
);
COMMENT ON TABLE  env.all_reports               IS 'Jasper rapor envanteri: ebml.report paketi (ebml.report.package) ve alt paketleri altındaki .dsxml dosyaları; ebml.enabled=true (--ebml) ile yapılan her taramada dosya başına bir kayıt.';
COMMENT ON COLUMN env.all_reports.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_reports.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_reports.project_id    IS 'Raporun ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_reports.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE; modül repo köküyse repo adı) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_reports.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_reports.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_reports.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_reports.package_name  IS 'Dosyanın klasöründen üretilen paket adı: modül klasörü ve kaynak kökü (ebml.source.roots, ör. src/main/java, src/main/resources) atılıp / yerine . konur, ör. tr.com.x.ebml.report; dosya doğrudan kaynak kökündeyse boş';
COMMENT ON COLUMN env.all_reports.file_name     IS 'Dosya adı, ör. MUSTERI_LISTESI.dsxml; ebml.file.name.with.extension=false ise uzantısız (MUSTERI_LISTESI)';
COMMENT ON COLUMN env.all_reports.file_path     IS 'Dosyanın repo içindeki yolu, ör. musteri-web/src/main/resources/tr/com/x/ebml/report/MUSTERI_LISTESI.dsxml';
COMMENT ON COLUMN env.all_reports.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_reports.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

-- Process'ler: process klasörünün (ebml.process.dir) hemen altındaki <numara>[-<ad> veya _<ad>][.par] klasörlerindeki
-- processdefinition.xml dosyaları: process/250001-RISM.par ve process/250001-RISM ikisi de process (.par zorunlu değil)
CREATE TABLE env.all_processes (
    id             bigserial      PRIMARY KEY,
    scan_run_id    bigint         NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_id     bigint         REFERENCES env.project (id),
    project_name   varchar(300),
    project_match  varchar(30)    NOT NULL,
    repo           varchar(200),
    module         varchar(300),
    process_id     bigint         NOT NULL,            -- klasör adının başındaki numara: 250001-RISM.par / 250001-RISM -> 250001
    process_name   varchar(500),                       -- processdefinition.xml içindeki label (Müşteri Değerlendirme)
    folder_name    varchar(500)   NOT NULL,            -- klasör adı olduğu gibi: 250001-RISM.par veya 250001-RISM
    file_path      varchar(1000)  NOT NULL,
    link           varchar(2000),
    created_at     timestamptz    NOT NULL DEFAULT now()
);
COMMENT ON TABLE  env.all_processes               IS 'Process envanteri: process klasörünün (ebml.process.dir, varsayılan process) hemen altındaki <numara>[-<ad> veya _<ad>][.par] adlı klasörlerde bulunan processdefinition.xml dosyaları, ör. process/250001-RISM.par veya process/250001-RISM (.par uzantısı zorunlu değil); ebml.enabled=true (--ebml) ile yapılan her taramada process başına bir kayıt.';
COMMENT ON COLUMN env.all_processes.id            IS 'Kayıt numarası; otomatik artar';
COMMENT ON COLUMN env.all_processes.scan_run_id   IS 'Kaydın ait olduğu tarama: env.exception_scan_run.id. Tarama kaydı silinince bu kayıt da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.all_processes.project_id    IS 'Process''in ana projesinin env.project.id değeri; project_name, env.project.project_name sütununda aranarak bulunur. Proje bulunamazsa (PROJECT_NOT_FOUND) boş; ebml.insert.unmatched=false ise projesi bulunamayanlar tabloya hiç yazılmaz';
COMMENT ON COLUMN env.all_processes.project_name  IS 'env.project.project_name sütununda aranan ana proje adı: ebml.project.name.from ayarına göre repo adı (REPO, varsayılan), repo slug''ı (REPO_SLUG), modül klasörünün adı (MODULE; modül repo köküyse repo adı) veya Bitbucket proje adı (BITBUCKET_PROJECT). İlk ad bulunamayıp ikinci aday (repo slug''ı / proje anahtarı) bulunduysa o yazılır; proje bulunamasa da doludur';
COMMENT ON COLUMN env.all_processes.project_match IS 'Proje eşleştirme sonucu: MATCHED (env.project''te tek kayıt bulundu), PROJECT_AMBIGUOUS (aynı adda birden fazla proje var, en küçük id seçildi) veya PROJECT_NOT_FOUND (bulunamadı, project_id boş). Ad karşılaştırması varsayılan olarak büyük/küçük harf duyarsızdır (ebml.project.case.insensitive)';
COMMENT ON COLUMN env.all_processes.repo          IS 'Dosyanın bulunduğu reponun slug''ı (Bitbucket repo slug''ı; git kaynağında adresteki repo adı, yerel kaynakta klasör adı)';
COMMENT ON COLUMN env.all_processes.module        IS 'Dosyanın bulunduğu modül: pom.xml, build.gradle(.kts), build.xml veya .project bulunan en yakın üst klasörün repo içindeki yolu, ör. musteri-web; bu klasör repo köküyse veya hiç yoksa ''(kök)'' yazılır';
COMMENT ON COLUMN env.all_processes.process_id    IS 'Process numarası: klasör adının başındaki sayı (250001-RISM.par veya 250001-RISM -> 250001). Adı <numara>[-<ad> veya _<ad>][.par] biçimine uymayan klasörlerdeki processdefinition.xml process sayılmaz, bu tabloya yazılmaz';
COMMENT ON COLUMN env.all_processes.process_name  IS 'Process adı: processdefinition.xml içindeki label özelliği (ör. Müşteri Değerlendirme); önce kök elemana, yoksa adında process geçen ilk elemana bakılır. Bulunamazsa veya dosya okunamazsa boş ve dosya tarama hatalarına PROCESS_LABEL koduyla yazılır';
COMMENT ON COLUMN env.all_processes.folder_name   IS 'Process klasörünün adı olduğu gibi, ör. 250001-RISM.par veya 250001-RISM (.par uzantısı zorunlu değil)';
COMMENT ON COLUMN env.all_processes.file_path     IS 'processdefinition.xml dosyasının repo içindeki yolu, ör. musteri-web/process/250001-RISM.par/processdefinition.xml';
COMMENT ON COLUMN env.all_processes.link          IS 'Dosyayı Bitbucket''ta (git kaynağında GitHub/GitLab''da) açan bağlantı, taranan commit''e sabitlenmiş; web adresi bilinmeyen kaynaklarda (yerel klasör, diğer git sunucuları) boş';
COMMENT ON COLUMN env.all_processes.created_at    IS 'Kaydın eklendiği an (DEFAULT now()); kayıtlar tek transaction''da yazıldığı için aynı taramanın tüm kayıtlarında aynıdır';

CREATE INDEX ix_all_screens_run         ON env.all_screens (scan_run_id);
CREATE INDEX ix_all_screens_project     ON env.all_screens (project_id);
CREATE INDEX ix_all_popups_run          ON env.all_popups (scan_run_id);
CREATE INDEX ix_all_popups_project      ON env.all_popups (project_id);
CREATE INDEX ix_all_regions_run         ON env.all_regions (scan_run_id);
CREATE INDEX ix_all_regions_project     ON env.all_regions (project_id);
CREATE INDEX ix_all_reports_run         ON env.all_reports (scan_run_id);
CREATE INDEX ix_all_reports_project     ON env.all_reports (project_id);
CREATE INDEX ix_all_processes_run       ON env.all_processes (scan_run_id);
CREATE INDEX ix_all_processes_project   ON env.all_processes (project_id);
CREATE INDEX ix_all_processes_process   ON env.all_processes (process_id);

-- ---------------------------------------------------------------------
--  Görünümler
-- ---------------------------------------------------------------------

-- Son taramanın kullanımları, sınıf ve metot bilgileriyle
CREATE VIEW env.v_exception_usage_latest AS
SELECT u.*,
       t.type_name,
       t.action,
       t.is_legacy
  FROM env.exception_usage u
  JOIN env.exception_usage_type t ON t.type_code = u.usage_type_code
 WHERE u.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE finished_at IS NOT NULL);

-- Son tarama: proje ve kullanım tipine göre sayılar (ekiplere gönderilecek özet)
CREATE VIEW env.v_exception_usage_summary AS
SELECT project_key,
       repo,
       usage_type_code,
       type_name,
       is_legacy,
       count(*)                                          AS usage_count,
       count(*) FILTER (WHERE NOT is_test)               AS non_test_count,
       count(*) FILTER (WHERE match_status <> 'MATCHED') AS unmatched_count
  FROM env.v_exception_usage_latest
 GROUP BY project_key, repo, usage_type_code, type_name, is_legacy;

-- Taramadan taramaya ilerleme: eski kullanım sayısı azalmalı
CREATE VIEW env.v_exception_usage_trend AS
SELECT r.id          AS scan_run_id,
       r.started_at,
       u.project_key,
       u.usage_type_code,
       count(*)      AS usage_count
  FROM env.exception_scan_run r
  JOIN env.exception_usage u ON u.scan_run_id = r.id
 WHERE r.finished_at IS NOT NULL
 GROUP BY r.id, r.started_at, u.project_key, u.usage_type_code;

-- Son taramanın hataları
CREATE VIEW env.v_exception_scan_error_latest AS
SELECT e.*
  FROM env.exception_scan_error e
 WHERE e.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE finished_at IS NOT NULL);

-- Son envanter taramasında proje bazında sayılar
CREATE VIEW env.v_ebml_inventory_latest AS
WITH last_run AS (
    SELECT max(id) AS id FROM env.exception_scan_run
     WHERE finished_at IS NOT NULL AND screen_count IS NOT NULL
), files AS (
    SELECT 'SCREEN' AS kind, project_id, project_name, project_match FROM env.all_screens   WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'POPUP',          project_id, project_name, project_match FROM env.all_popups    WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'REGION',         project_id, project_name, project_match FROM env.all_regions   WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'REPORT',         project_id, project_name, project_match FROM env.all_reports   WHERE scan_run_id = (SELECT id FROM last_run)
    UNION ALL
    SELECT 'PROCESS',        project_id, project_name, project_match FROM env.all_processes WHERE scan_run_id = (SELECT id FROM last_run)
)
SELECT project_id,
       project_name,
       project_match,
       count(*) FILTER (WHERE kind = 'SCREEN') AS screen_count,
       count(*) FILTER (WHERE kind = 'POPUP')  AS popup_count,
       count(*) FILTER (WHERE kind = 'REGION') AS region_count,
       count(*) FILTER (WHERE kind = 'REPORT') AS report_count,
       count(*) FILTER (WHERE kind = 'PROCESS') AS process_count
  FROM files
 GROUP BY project_id, project_name, project_match;

-- Örnek: son taramada repo ve hata türüne göre hata sayıları
--   SELECT project_key, repo, stage, count(*) FROM env.v_exception_scan_error_latest
--    GROUP BY project_key, repo, stage ORDER BY count(*) DESC;

-- Örnek sorgu: son taramadaki eski kullanımlar, sınıf ve metot envanteriyle.
-- id'ler envanter yeniden yüklenince değişebileceği için sınıf adıyla ve metot adıyla
-- (imzanın "(" öncesi) eşleştirilir.
--   SELECT c.fqcn, m.name, u.usage_type_code, u.line_no, u.message
--     FROM env.v_exception_usage_latest u
--     JOIN env.java_class  c ON c.fqcn = u.class_fqcn
--     LEFT JOIN env.java_method m ON m.class_id = c.id
--                               AND split_part(m.name, '(', 1) = split_part(u.method_signature, '(', 1)
--    WHERE u.is_legacy
--    ORDER BY c.fqcn, u.line_no;

-- Eski taramaları temizlemek için (kullanımlar ve hatalar CASCADE ile silinir):
--   DELETE FROM env.exception_scan_run WHERE started_at < now() - interval '180 days';
--
-- Örnek sorgu: bir projenin son taramadaki ekranları
--   SELECT s.file_name, s.package_name, s.link
--     FROM env.all_screens s
--    WHERE s.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE screen_count IS NOT NULL)
--      AND s.project_id = 42
--    ORDER BY s.package_name, s.file_name;
--
-- Örnek sorgu: bir projenin son taramadaki process'leri
--   SELECT p.process_id, p.process_name, p.folder_name, p.link
--     FROM env.all_processes p
--    WHERE p.scan_run_id = (SELECT max(id) FROM env.exception_scan_run WHERE process_count IS NOT NULL)
--      AND p.project_id = 42
--    ORDER BY p.process_id;

-- =====================================================================
--  Hata yutan catch blokları: boş, sadece loglayan veya loglayıp return eden
-- =====================================================================
CREATE TABLE env.swallowed_exceptions (
    id               bigserial    PRIMARY KEY,
    scan_run_id      bigint       NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    -- Tarama anındaki env.java_class / env.java_method id'leri. FK yok: o tablolar her
    -- yüklemede TRUNCATE + INSERT ile yenilendiği için FK yüklemeyi engeller ve id'ler
    -- sonraki yüklemede değişebilir. Kalıcı eşleştirme için class_fqcn ve method_signature
    -- sütunlarındaki metin kullanılmalı.
    class_id         bigint,
    method_id        bigint,

    -- Eşleştirme sonucu (env.exception_usage.match_status ile aynı değerler):
    --   MATCHED | METHOD_AMBIGUOUS | METHOD_NOT_FOUND | NO_METHOD | CLASS_AMBIGUOUS | CLASS_NOT_FOUND
    match_status     varchar(30)  NOT NULL,
    match_note       varchar(1000),

    project_key      varchar(100),
    repo             varchar(200),
    file_path        varchar(1000) NOT NULL,
    line_no          integer      NOT NULL,
    class_fqcn       varchar(1000) NOT NULL,           -- kodda bulunan sınıf (anonim sınıf dahil)
    method_signature varchar(1000),                    -- kodda bulunan metot: getRate(String, String)
    exception_class  varchar(300),                     -- yakalanan exception: CSException (multi-catch'te A | B)
    catch_kind       varchar(20)  NOT NULL,            -- EMPTY | LOG_ONLY | LOG_AND_RETURN | RETURN_ONLY
    link             varchar(2000),
    created_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_swallowed_exceptions_method_needs_class CHECK (method_id IS NULL OR class_id IS NOT NULL)
);

COMMENT ON TABLE  env.swallowed_exceptions IS 'Hata yutan catch blokları: hatayı çağırana iletmeden akışa devam eden her catch bloğu için bir kayıt, bulunduğu sınıf ve metodun envanterdeki id''leriyle. Nasıl yuttuğu catch_kind sütunundadır (EMPTY, LOG_ONLY, LOG_AND_RETURN, RETURN_ONLY). Kapsamı scan.swallowed.scope belirler: TARGET (varsayılan) aranan exception sınıflarından (exception.classes ve repoda onlardan türeyen alt sınıflar) birini yakalayanlar, ALL tipine bakmadan hepsi, OFF hiçbiri. db.enabled=true ile yapılan her taramada yazılır; her kayıt bir taramaya bağlıdır.';
COMMENT ON COLUMN env.swallowed_exceptions.id IS 'Kayıt numarası (otomatik artan)';
COMMENT ON COLUMN env.swallowed_exceptions.scan_run_id IS 'Kaydın ait olduğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.swallowed_exceptions.class_id IS 'Tarama anında catch bloğunun bulunduğu sınıfın env.java_class id''si (db.class.*); CLASS_NOT_FOUND ise boş. FK yok, envanter yeniden yüklenince değişebilir: kalıcı eşleştirme için class_fqcn kullanılmalı';
COMMENT ON COLUMN env.swallowed_exceptions.method_id IS 'Tarama anında catch bloğunun bulunduğu metodun env.java_method id''si (db.method.*); metot bulunamadıysa (METHOD_NOT_FOUND, NO_METHOD, CLASS_NOT_FOUND veya metodu bulunamayan CLASS_AMBIGUOUS) boş. FK yok; class_id boşken dolu olamaz';
COMMENT ON COLUMN env.swallowed_exceptions.match_status IS 'Envanter eşleştirmesinin sonucu: MATCHED (sınıf ve metot bulundu), METHOD_AMBIGUOUS (aynı adda birden fazla metot, en küçük id seçildi), METHOD_NOT_FOUND (sınıf bulundu, metot bulunamadı), NO_METHOD (catch bloğu static/instance blokta veya metot dışında), CLASS_AMBIGUOUS (aynı FQCN birden fazla kayıtta, en küçük id seçildi), CLASS_NOT_FOUND (sınıf envanterde yok; db.insert.unmatched=false ise bu kayıtlar yazılmaz)';
COMMENT ON COLUMN env.swallowed_exceptions.match_note IS 'Eşleştirme sonucunun açıklaması, ör. CLASS_NOT_FOUND için denenen adlar ("Aranan: a.b.Dis$Ic, a.b.Dis.Ic, a.b.Dis"), overload seçimi, dış sınıfa veya anonim sınıfı içeren sınıfa düşülmesi; açıklama yoksa boş';
COMMENT ON COLUMN env.swallowed_exceptions.project_key IS 'Reponun projesi: Bitbucket proje anahtarı; source=GIT için adresteki sahip (owner, bulunamazsa GIT), source=LOCAL için klasör yapısından çıkarılan proje adı';
COMMENT ON COLUMN env.swallowed_exceptions.repo IS 'Repo slug''ı (Bitbucket/git adresindeki repo adı; source=LOCAL için repo klasörünün adı)';
COMMENT ON COLUMN env.swallowed_exceptions.file_path IS 'Catch bloğunun bulunduğu .java dosyasının repo içindeki yolu, / ayraçlı (ör. src/main/java/a/b/Dis.java)';
COMMENT ON COLUMN env.swallowed_exceptions.line_no IS 'Catch bloğunun (catch anahtar kelimesinin) başladığı satır; bilinmiyorsa 0';
COMMENT ON COLUMN env.swallowed_exceptions.class_fqcn IS 'Catch bloğunun kodda bulunduğu sınıfın paketle birlikte tam adı; iç sınıflar nokta ile, anonim sınıflar <anonim Tip> olarak yazılır (ör. a.b.Dis.Ic.<anonim Runnable>). Envanterle kalıcı eşleştirme bu sütunla yapılmalı';
COMMENT ON COLUMN env.swallowed_exceptions.method_signature IS 'Catch bloğunun kodda bulunduğu üye: metot imzası (ör. getRate(String, String)), constructor için "Ad(String) [constructor]", bloklarda <static blok> / <instance blok>, alan tanımında <alan: ad>; üye yoksa boş';
COMMENT ON COLUMN env.swallowed_exceptions.exception_class IS 'Yakalanan exception sınıfının koddaki adı. scan.swallowed.scope=TARGET ise aranan sınıf veya repoda ondan türeyen alt sınıf (ör. CSException), multi-catch''te sadece aranan sınıflar; ALL ise yakalanan tüm tipler (ör. Exception, IOException). Birden fazla tip " | " ile ayrılır';
COMMENT ON COLUMN env.swallowed_exceptions.catch_kind IS 'Hatanın nasıl yutulduğu: EMPTY (gövde boş veya sadece yorum), LOG_ONLY (gövdede sadece log çağrıları var, ör. log.error("x", e) veya e.printStackTrace()), LOG_AND_RETURN (log çağrılarından sonra return, ör. log.error(...); return null;), RETURN_ONLY (log olmadan sadece return, ör. return null, return false, return "", return EMPTY, return;). İki return türünde de dönen değer hatayı çağırana taşıyorsa kayıt yazılmaz: yakalanan değişken (adı ne olursa olsun: e, ex, t) ifadede geçiyorsa (return handle(ex), return new HataCevabi(ex.getMessage())), adı Exception/Error/Throwable ile biten bir tip oluşturuluyorsa (return new BusinessException("x")) veya catch''in bulunduğu metodun dönüş tipi böyleyse. Log çağrısı sayılanlar: printStackTrace, System.out/err, adı scan.swallowed.log.names''te olan (varsayılan log, logger) veya logger ile biten nesnelerin metotları, getLogger()/getLog() sonucunun metotları, log ile başlayan nesnesiz çağrılar (logError(e)) ve sadece log içeren if blokları';
COMMENT ON COLUMN env.swallowed_exceptions.link IS 'Dosyayı ilgili satırda açan Bitbucket (source=GIT ise GitHub/GitLab) bağlantısı, taranan commit''e sabitlenmiş; source=LOCAL veya bağlantı üretilemeyen git adreslerinde boş';
COMMENT ON COLUMN env.swallowed_exceptions.created_at IS 'Kaydın eklendiği an (now()); tarama tek transaction''da yazıldığı için transaction''ın başladığı an olur ve aynı taramanın tüm kayıtlarında aynıdır';

CREATE INDEX ix_swallowed_exceptions_run ON env.swallowed_exceptions (scan_run_id);
CREATE INDEX ix_swallowed_exceptions_class ON env.swallowed_exceptions (class_id);
CREATE INDEX ix_swallowed_exceptions_method ON env.swallowed_exceptions (method_id);


-- ---------------------------------------------------------------------
--  Taranan repolar: her taramada taranan her repo için bir satır,
--  bulgu çıkmasa da yazılır. Rapor API'si (/api/reports/excel) bir
--  reponun en son taramasını ve özet sayılarını buradan okur.
-- ---------------------------------------------------------------------
CREATE TABLE env.exception_scan_repo (
    id                           bigserial    PRIMARY KEY,
    scan_run_id                  bigint       NOT NULL REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    project_key                  varchar(100) NOT NULL,
    project_name                 varchar(300),
    repo                         varchar(200) NOT NULL,
    branch                       varchar(200),
    commit_id                    varchar(64),
    status                       varchar(20)  NOT NULL,             -- SCANNED | CLONE_FAILED | SCAN_FAILED
    java_file_count              integer,
    target_file_count            integer,
    parse_failure_count          integer,
    usage_count                  integer,
    swallowed_error_count        integer,
    custom_exception_class_count integer,
    error_count                  integer,
    clone_seconds                integer,
    scan_seconds                 integer,
    created_at                   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_exception_scan_repo UNIQUE (scan_run_id, project_key, repo)
);

COMMENT ON TABLE  env.exception_scan_repo IS 'Taranan repolar: her taramada taranan her repo için bir satır, bulgu çıkmasa da yazılır. Bir reponun en son ne zaman tarandığı, temiz mi çıktığı yoksa taranamadı mı bu tablodan anlaşılır; rapor API''si (/api/reports/excel, /api/reports/repos) buradan okur';
COMMENT ON COLUMN env.exception_scan_repo.id IS 'Kayıt numarası (otomatik artan)';
COMMENT ON COLUMN env.exception_scan_repo.scan_run_id IS 'Kaydın ait olduğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.exception_scan_repo.project_key IS 'Reponun projesi: Bitbucket proje anahtarı (ör. A); source=GIT için adresteki sahip, source=LOCAL için klasör yapısından çıkarılan proje adı';
COMMENT ON COLUMN env.exception_scan_repo.project_name IS 'Projenin görünen adı (Bitbucket''taki proje adı)';
COMMENT ON COLUMN env.exception_scan_repo.repo IS 'Projedeki repo: slug (Bitbucket/git adresindeki repo adı, ör. A3); diğer tablolardaki repo sütunuyla aynı değer';
COMMENT ON COLUMN env.exception_scan_repo.branch IS 'Taranan dal';
COMMENT ON COLUMN env.exception_scan_repo.commit_id IS 'Taranan commit; iki tarama arasında kodun değişip değişmediği buradan görülür';
COMMENT ON COLUMN env.exception_scan_repo.status IS 'SCANNED (tarandı; bulgu olmayabilir. İstenen dal yoksa varsayılan dal taranmış olabilir: branch sütunu ve env.exception_scan_error''daki BRANCH_FALLBACK kaydı), CLONE_FAILED (klonlanamadı, taranmadı), SCAN_FAILED (taranırken hata, bellek yetmedi veya dosyalar listelenemedi; ayrıntı env.exception_scan_error''da)';
COMMENT ON COLUMN env.exception_scan_repo.java_file_count IS 'Repodaki .java dosyası sayısı (scan.exclude.dirs hariç)';
COMMENT ON COLUMN env.exception_scan_repo.target_file_count IS 'Aranan exception sınıflarından birinin adı geçen dosya sayısı';
COMMENT ON COLUMN env.exception_scan_repo.parse_failure_count IS 'Ayrıştırılamayan veya okunamayan dosya sayısı';
COMMENT ON COLUMN env.exception_scan_repo.usage_count IS 'Repodan env.exception_usage''a yazılan kullanım sayısı';
COMMENT ON COLUMN env.exception_scan_repo.swallowed_error_count IS 'Repodan env.swallowed_exceptions''a yazılan hata yutan catch bloğu sayısı; scan.swallowed.scope=OFF veya tablo yoksa boş';
COMMENT ON COLUMN env.exception_scan_repo.custom_exception_class_count IS 'Aranan exception sınıflarından (exception.classes, ör. CustomException) doğrudan veya dolaylı türeyen ve bu repoda tanımlanan sınıf sayısı; aranan sınıfların kendisi sayılmaz. Sınıfların listesi tarama sonundaki Excel''in Alt Sınıflar sayfasındadır';
COMMENT ON COLUMN env.exception_scan_repo.error_count IS 'Repodaki tarama hatası sayısı (env.exception_scan_error)';
COMMENT ON COLUMN env.exception_scan_repo.clone_seconds IS 'Klonlama / güncelleme süresi (saniye)';
COMMENT ON COLUMN env.exception_scan_repo.scan_seconds IS 'Tarama süresi (saniye)';
COMMENT ON COLUMN env.exception_scan_repo.created_at IS 'Kaydın eklendiği an (now()); aynı taramanın tüm kayıtlarında aynıdır';

CREATE INDEX ix_exception_scan_repo_repo ON env.exception_scan_repo (lower(project_key), lower(repo), scan_run_id);
