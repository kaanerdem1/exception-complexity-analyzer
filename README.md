# Exception-Complexity Analyzer

Exception tarama ve PMD tabanlı karmaşıklık/risk analizi tek repoda.

## Exception kullanım tarayıcı

Bitbucket'taki tüm repoları, bir GitHub/GitLab adresini veya bilgisayarınızdaki bir projeyi tarayıp belirttiğiniz exception sınıfının, tanımladığınız argüman şekillerine uyan kullanımlarını (örneğin `throw new CustomException(0, "deneme")`) bulur ve ekiplere gönderilebilecek Excel raporları üretir.

Tarama metin araması değil, Java kaynak kodunu gerçekten ayrıştırarak (JavaParser) yapılır. Bu sayede her kullanımın sınıfı, metodu ve satırı doğru bulunur; yorum satırları ve string içindeki geçişler yanlış sonuç üretmez; exception'a hangi tür argümanla mesaj verildiği de sınıflandırılır.

## Gereksinimler

Tarayıcıyı çalıştıracak makinede JDK 17 veya üstü ve derleme için Maven olmalıdır. Bitbucket veya git adresinden tarama yapılacaksa PATH'te Git 2.31 veya üstü de gerekir; yerel klasör taramasında git gerekmez. Taranan kodun Java 6 olması sorun değildir. Tarayıcı Bitbucket'ta hiçbir şeyi değiştirmez; sadece okuma yetkisi yeterlidir.

## Derleme

```bash
mvn -q package
```

Çıktı: `target/system-code-analyzer-1.0.0.jar` (tüm bağımlılıklar içinde). Aynı jar komut satırı aracı olarak veya `--server` ile REST API olarak çalışır (bkz. [REST API ve Swagger](#rest-api-ve-swagger)).

## Ayarlar

`scanner.properties` dosyasındaki açıklamalar her ayarı anlatıyor. En önemlileri:

| Ayar | Açıklama |
|---|---|
| `source` | `server` (Bitbucket Server / Data Center), `cloud` (bitbucket.org), `git` (GitHub vb.) veya `local` |
| `bitbucket.url` | Server için Bitbucket adresi |
| `bitbucket.workspace` | Cloud için workspace adı |
| `git.urls` | `source=git` için virgülle ayrılmış git adresleri |
| `local.dir` | `source=local` için taranacak klasör |
| `bitbucket.projects` | Sadece bu projeleri tara (boşsa erişilebilen tüm projeler) |
| `bitbucket.include.repos` | Sadece bu repoları tara (boşsa hepsi). `PROJE/repo` sadece o projeyi daraltır, aşağıya bakın |
| `bitbucket.exclude.repos` | Bu repoları atla: repo adı, `PROJE/repo` veya `*` ile kalıp (`z_atil_*`) |
| `exception.classes` | Aranacak exception'ın tam adı, örn. `com.firma.framework.CustomException` |
| `exception.ignore.classes` | Eski sınıftan türeyen ama yeni yapıya ait olan sınıflar (örn. yeni `AppException`) |
| `pattern.N.args` | Aranacak constructor şekli, örn. `0,STRING` (aşağıya bakın) |
| `scan.throw.only` | `true` ise sadece doğrudan `throw new ...` şeklindekiler |
| `scan.global.hierarchy` | `true` (varsayılan): alt sınıflar önce tüm repolardan birlikte çıkarılır; ortak bir repoda tanımlanan `X extends CustomException` diğer repolarda da aranır, repolar arası zincirler de çözülür. Bunun için önce tüm repolar klonlanıp sınıf başlıkları okunur, kullanım taraması sonra başlar. `false`: her repo sadece kendi içinde tanımlanan alt sınıfları bilir |
| `git.branch` | Boşsa varsayılan dal; `develop` gibi bir dal da verilebilir |
| `git.branch.fallback` | `true` (varsayılan): `git.branch`'teki dal repoda yoksa reponun varsayılan dalı taranır; logda uyarı çıkar, "Tarama Hataları"na ve `env.exception_scan_error`'a `BRANCH_FALLBACK` kaydı yazılır, "Dal" sütununda taranan dal görünür. `false`: o repo taranmaz (`CLONE` hatası) |

### Repo seçimi

`bitbucket.include.repos` ve `bitbucket.exclude.repos` aynı yazımı kullanır: repo adı (`repo1`), proje ile repo adı (`A/repo1`) veya `*` ile kalıp (`z_atil_*`, `A/test-*`). Büyük/küçük harf duyarsızdır.

- `include` boşsa tüm repolar alınır. `PROJE/kalıp` şeklindeki bir kayıt sadece o projeyi daraltır; listede adı geçmeyen projelerin tüm repoları alınır. Proje adı olmayan kayıt (`core-*`) tüm projeleri daraltır.
- `exclude` her zaman en son uygulanır; include ile seçilenlerden de çıkarır. Proje adı olmayan kayıt tüm projelerde geçerlidir.
- `include` içindeki bir kayıt hiçbir repoyla eşleşmezse logda uyarı çıkar (yazım hatası olabilir).

| Ayarlar | Taranan |
|---|---|
| `projects=A,B`, `include=A/repo1,A/repo2` | A'dan sadece `repo1` ve `repo2`, B'nin tamamı |
| `projects=A,B`, `exclude=z_atil_*` | A ve B'de `z_atil_` ile başlayanlar dışındaki tüm repolar |
| `projects=A,B,C`, `include=A/repo1,A/repo2`, `exclude=z_atil_*` | A'dan `repo1` ve `repo2`; B ve C'den `z_atil_` ile başlayanlar dışındakiler |
| `include=core-*` | Tüm projelerden sadece adı `core-` ile başlayanlar |

Token'ı dosyaya yazmak yerine ortam değişkeniyle verin:

```bash
export BITBUCKET_TOKEN=xxxxxxxx          # Windows: set BITBUCKET_TOKEN=xxxxxxxx
export BITBUCKET_USERNAME=kullanici      # gerekiyorsa
```

Kullanıcı adı boş bırakılırsa `Authorization: Bearer <token>` (Bitbucket Server HTTP access token), doluysa Basic auth kullanılır. Bitbucket Server'da proje ve repo okuma yetkisi olan kişisel bir HTTP access token yeterlidir. Bitbucket Cloud'da depo okuma yetkisi olan bir API token kullanın; git klonlamada kimlik doğrulama sorunu yaşarsanız `git.protocol=ssh` ile makinedeki SSH anahtarını kullanmak en sorunsuz yoldur.

Şirket içi Bitbucket'ın sertifikası JDK tarafından tanınmıyorsa kalıcı çözüm, şirket CA sertifikasını JDK truststore'una eklemektir. Geçici olarak `ssl.insecure=true` da kullanılabilir.

## Çalıştırma

### Hızlı deneme: kendi bilgisayarınızdaki bir proje

Ayar dosyası gerekmez, her şey komut satırından verilebilir:

```bash
java -jar target/system-code-analyzer-1.0.0.jar \
     --local C:/projeler/musteri-servisi \
     --class com.firma.framework.exception.CustomException \
     --pattern 0,STRING,*
```

`--local` tek bir proje klasörü (git reposu olması gerekmez, zip'ten açılmış bir klasör de olur) veya içinde birden fazla proje bulunan bir klasör olabilir. İçinde `pom.xml`, `build.xml`, `build.gradle`, `.project` ya da `src` bulunan klasör tek proje olarak taranır; bunlar yoksa her alt klasör ayrı bir proje sayılır.

### GitHub, GitLab veya herhangi bir git adresi

```bash
java -jar target/system-code-analyzer-1.0.0.jar \
     --git https://github.com/sahip/repo \
     --class com.firma.framework.exception.CustomException \
     --pattern 0,STRING,*
```

`--git` birden fazla kez verilebilir. `https://github.com/sahip/repo/tree/develop` şeklindeki adreslerde dal adresten alınır; `--branch` ile de verilebilir. Özel (private) repolarda makinenizdeki git kimlik bilgileri (Git Credential Manager) veya SSH adresi (`git@github.com:sahip/repo.git`) kullanılır. GitHub ve GitLab adreslerinde rapordaki bağlantılar ilgili satırı açar.

### Bitbucket

```bash
java -jar target/system-code-analyzer-1.0.0.jar scanner.properties
```

Repolar `work.dir` (varsayılan `./scannedRepos`) altına sığ (`--depth 1`) olarak klonlanır; tekrar çalıştırıldığında sadece güncellenir. İlk denemede `bitbucket.projects` ile tek bir projeyle başlamanızı öneririm.

### Komut satırı parametreleri

Parametreler ayar dosyasındaki değerlerin üzerine yazar; ayar dosyası yoksa varsayılanlarla çalışılır. `--pattern` verilirse dosyadaki desenler yok sayılır.

| Parametre | Açıklama |
|---|---|
| `--local <klasör>` | Bilgisayardaki bir projeyi veya proje klasörlerini tara |
| `--git <adres>` | Git adresini klonlayıp tara (birden fazla verilebilir) |
| `--branch <dal>` | Taranacak dal |
| `--class <sınıf>` | Aranacak exception'ın tam adı (birden fazla verilebilir) |
| `--pattern <desen>` | Aranacak argüman şekli, örn. `0,STRING` (birden fazla verilebilir) |
| `--throw-only` | Sadece doğrudan `throw new ...` şeklindekiler |
| `--lenient` | Import ve paket kontrolü yapmadan sadece sınıf adına göre eşleştir |
| `--threads <n>` | Aynı anda ayrıştırılacak repo sayısı (varsayılan 4) |
| `--git-threads <n>` | Aynı anda klonlanacak/güncellenecek repo sayısı (varsayılan 8) |
| `--out <klasör>` | Raporların yazılacağı klasör (varsayılan `./scanReports`) |
| `--console-limit <n>` | Konsola yazılacak en fazla kullanım (varsayılan 200, `0` = yazma) |
| `--help` | Yardım |

Windows komut satırında satır devamı için `\` yerine `^` kullanın ya da komutu tek satırda yazın. `*` içeren desenleri Linux/macOS'ta tırnak içinde verin: `--pattern "0,STRING,*"`.

## Raporlar

Bulunan kullanımlar (varsayılan olarak ilk 200 tanesi) dosya, satır, sınıf, metot ve kodla birlikte konsola da yazılır; tam liste Excel raporundadır.

`report.excel=false` ile hiç Excel raporu yazılmaz; sonuçlar sadece veritabanına (`db.enabled=true`) ve konsola gider. Swagger'dan bir defalığına `"properties": {"report.excel": "false"}` ile de kapatılabilir; bu durumda `GET /api/scans/{id}/report` 404 döner.

`output.dir/yyyyMMdd_HHmm/` altında:

- `exception_scan_report.xlsx`: tüm projeleri içeren ana rapor (açılışta Kullanımlar sayfası görünür)
- `projects/report_<PROJE>.xlsx`: her Bitbucket projesi için ayrı rapor, doğrudan ilgili ekibe gönderilebilir

Her raporda şu sayfalar bulunur (Excel'de sekmeler pencerenin altında, Numbers'da üstündedir):

**Özet:** toplam sayılar ve her desen için repo bazında dağılım, en çok eşleşmesi olan repo en üstte.

**Kullanımlar:** desene uyan her kullanım. Proje, repo, modül, dosya, sınıf, metot, satır, eşleşen desen, yapılacak iş, id değeri, mesaj tipi ve metni, kodun kendisi ve Bitbucket'ta ilgili satırı açan bağlantı. Bağlantılar taranan commit'e sabitlendiği için kod değişse de doğru satırı gösterir.

**Mesajlar:** koddaki string mesajların tekilleştirilmiş listesi, kaç yerde kullanıldığıyla birlikte. Sarı sütunlar (önerilen hata kodu, TR ve EN kullanıcı mesajı) doldurulduğunda hata kataloğunun ilk taslağı çıkmış olur. Birleştirilen değişkenler `{0}`, `{1}` şeklinde gösterilir: `"Kayıt bulunamadı: " + id` → `Kayıt bulunamadı: {0}`.

**Yutulan Exception'lar** (`report.swallowed=true`, varsayılan): hatayı yutan catch blokları; `env.swallowed_exceptions` tablosuna yazılan kayıtların aynısıdır (kapsamı `scan.swallowed.scope`). Özet sayfasında türe göre sayıları da görünür. `report.swallowed=false` ile sayfa oluşmaz; veritabanına yazma bundan etkilenmez.

**Alt Sınıflar:** projelerde bu exception'dan türetilmiş sınıflar. Aynı repo içindeki kullanımları otomatik olarak taranır. Başka repolarda da kullanılıyorsa sınıfın tam adını `exception.classes`'a ekleyip taramayı tekrarlayın.

**Tarama Hataları:** klonlanamayan repolar ve ayrıştırılamayan dosyalar.

## Desenler

Desenler, constructor'a geçilen argümanların şeklini tarif eder. Sırayla denenir; bir kullanım ilk uyduğu desenle raporlanır, hiçbirine uymayan kullanımlar rapora girmez. Hiç desen tanımlanmazsa sınıfın tüm kullanımları raporlanır.

| Değer | Eşleştiği argüman |
|---|---|
| `0`, `-1`, `99` | Tam olarak bu sayı (`0`, `0L`, `0x0` aynı kabul edilir) |
| `INT` | Herhangi bir sayı |
| `STRING` | Koda yazılmış mesaj: sabit string, `"a" + x` birleştirme veya `String.format(...)` |
| `STRING_LITERAL` | Sadece sabit string |
| `GETMESSAGE` | `e.getMessage()` |
| `CAUSE` | Catch'te yakalanan exception değişkeni |
| `CONST` | Büyük harfli sabit, örn. `HataKodlari.KAYIT_YOK` |
| `VAR` | Diğer değişken veya ifade |
| `ANY` | Herhangi bir argüman |
| `*` | Sadece sonda: kalan argümanlar ne olursa olsun |

Örnekler:

| Kod | Uyan desen |
|---|---|
| `throw new CustomException(0, "deneme");` | `0,STRING` |
| `throw new CustomException(0, "Kayıt yok: " + id);` | `0,STRING` |
| `throw new CustomException(0, "deneme", e);` | `0,STRING,*` veya `0,STRING,CAUSE` |
| `throw new CustomException(0, mesaj);` | `0,VAR` veya `0,ANY` |
| `throw new CustomException(1042, params);` | Hiçbiri (id 0 değil) |

Argüman olarak belirli bir sabit veya metot çağrısı da aranabilir:

| Değer | Eşleştiği argüman |
|---|---|
| `GENERALERRORCODE.ERROR_CODE` | Bu sabitin kendisi. Tam nitelikli yazım, static import ile `ERROR_CODE` ve sabitin adıyla aynı string (`"ERROR_CODE"`) da kabul edilir |
| `getErrorCode()` | Bu metodun herhangi bir nesne üzerinden çağrısı, örn. `e.getErrorCode()` |
| `'errorCode'` | Tam olarak bu string (tek veya çift tırnakla) |
| `A\|B` | Alternatifler: A veya B, örn. `GENERALERRORCODE.ERROR_CODE\|'errorCode'` |

Sabitin değeri adından farklıysa (örneğin `ERROR_CODE = "errorCode"`) string hâlini alternatif olarak ekleyin. Sabit kapalı bir jar içindeyse değerini JDK ile gelen `javap` aracıyla görebilirsiniz: `javap -constants -cp framework.jar com.firma.GENERALERRORCODE`

Bir kullanımın neden rapora girmediğini anlamak için desenleri geçici olarak kaldırıp (tüm kullanımlar raporlanır) Kullanımlar sayfasındaki koda bakabilirsiniz.

## PostgreSQL envanterine kayıt

Tarayıcı bulduğu kullanımları, sınıf ve metot envanterini tutan PostgreSQL tablolarıyla eşleştirip yeni bir kullanım tablosuna yazabilir. Böylece geçiş ilerlemesi SQL ile takip edilebilir.

### Kurulum

`sql/exception_scanner_postgres.sql` dosyasını veritabanında bir kez çalıştırın (ilk kurulum). Tüm tabloları ve görünümleri, exception kullanımlarını ve ekran/rapor/process envanterini birlikte oluşturur. Exception tarafı için oluşan nesneler:

| Nesne | İçerik |
|---|---|
| `env.exception_usage_type` | Kullanım tipleri (desenlerin karşılığı). Tarayıcı her çalışmada ayar dosyasındaki desenlerle günceller |
| `env.exception_scan_run` | Her tarama çalıştırması bir kayıt |
| `env.exception_usage` | Kullanımlar; `class_id` → `env.java_class`, `method_id` → `env.java_method`, `usage_type_code` → kullanım tipi |
| `env.exception_scan_error` | Tarama sırasında karşılaşılan hatalar (klonlanamayan repo, ayrıştırılamayan dosya vb.); `stage` hatanın türü: `CLONE`, `BRANCH_FALLBACK` (istenen dal yok, varsayılan dal tarandı), `SCAN`, `OUT_OF_MEMORY`, `FILE_LISTING`, `EBML_LISTING`, `PARSE`, `READ`, `SKIPPED`, `PROCESS_LABEL` (Excel'deki "Aşama" sütunuyla aynı) |
| `env.swallowed_exceptions` | Hata yutan catch blokları; `catch_kind` nasıl yuttuğunu gösterir: `EMPTY` (gövde boş veya sadece yorum), `LOG_ONLY` (sadece log, ör. `log.error("x", e)`, `e.printStackTrace()`), `LOG_AND_RETURN` (log ve ardından `return`), `RETURN_ONLY` (log olmadan sadece `return`: `null`, `false`, `""`, `EMPTY`, `return;` ...). Dönen değer hatayı çağırana taşıyorsa (yakalanan değişken ifadede geçiyor, ör. `return handle(ex)`; `...Exception` tipi oluşturuluyor; ya da metodun dönüş tipi exception) yutma sayılmaz). Log sayılan nesne adları `scan.swallowed.log.names` ile verilir. `class_id` / `method_id` kullanımlardaki gibi eşleştirilir. `report.excel` ve `report.swallowed` ayarlarından bağımsız olarak `db.enabled=true` ile yazılır. Kapsamı `scan.swallowed.scope` belirler: `TARGET` (varsayılan) aranan exception'ı yakalayanlar, `ALL` tipine bakmadan hepsi (`catch (Exception e) { }` dahil; aranan sınıf geçmeyen dosyalar da ayrıştırıldığı için tarama uzar), `OFF` hiçbiri |
| `env.exception_scan_repo` | Her taramada taranan her repo için bir satır (bulgu çıkmasa da): dal, commit, `status` (`SCANNED`, `CLONE_FAILED`, `SCAN_FAILED`) ve sayılar (Java dosyası, kullanım, yutulan exception, `custom_exception_class_count`, hata, süreler). Rapor API'si bir reponun en son taramasını buradan bulur |
| `env.v_exception_usage_latest` | Son taramanın kullanımları |
| `env.v_exception_usage_summary` | Son tarama, proje ve tipe göre sayılar |
| `env.v_exception_usage_trend` | Taramadan taramaya sayıların değişimi |
| `env.v_exception_scan_error_latest` | Son taramanın hataları |

Dosya boş bir şemaya bir kez çalıştırılmak içindir; nesneler zaten varsa hata verir. `env.exception_scan_error` tablosu yoksa tarama yine kullanımları yazar, hatalar için logda uyarı verir.

Mevcut tablolarınızın sütun adları farklıysa hem SQL dosyasındaki `REFERENCES` satırlarını hem de `db.*` ayarlarını güncelleyin.

### Çalıştırma

```bash
export DB_USER=kullanici DB_PASSWORD=sifre
java -jar target/system-code-analyzer-1.0.0.jar tarama.properties --db --db-note "Ekim takibi"
```

İlk seferde `--db-dry-run` ile deneyin: eşleştirme yapılır ve sonucu Excel'deki "DB Sınıf Id", "DB Metot Id", "Eşleşme" sütunlarında görünür, ama tabloya bir şey yazılmaz.

`--db-note` (veya `db.note`) verilmezse tarama kaydının `note` sütununa taramanın başladığı an `21.10.2026 11:50:34 tarihli tarama` biçiminde yazılır (saat dilimi `db.note.zone`, varsayılan `Europe/Istanbul`).

Her çalıştırma yeni bir `scan_run` kaydı açar ve kullanımları ona bağlar; aynı tarama iki kez çalışınca veri karışmaz. Kayıt tek transaction'da yapılır: yazma yarıda hata verirse hiçbir şey kaydedilmez. Veritabanına bağlanılamazsa raporlar yine eşleştirmesiz yazılır.

### Eşleştirme

**Sınıf:** Kullanımın bulunduğu sınıfın FQCN'i `fqcn` sütununda aranır. İç içe sınıflar için hem `a.b.Dis$Ic` hem `a.b.Dis.Ic` denenir; bulunamazsa dıştaki sınıfa kadar kısaltılır. Anonim sınıf içindeki kullanımlar, onu içeren sınıfa ve o sınıftaki metoda eşlenir.

**Metot:** Tablodaki ad `(` karakterine kadar kesilerek karşılaştırılır; niteleyici ve dönüş tipi de atılır. Bu yüzden `getRate`, `getRate(String a, String b)`, yarım kalmış `getRate(String a,` ve `public static CSBag getRate(CSBag inBag)` aynı metot sayılır. Aynı adda birden fazla metot (overload) varsa, addaki veya `db.method.signature.column` sütunundaki parametrelerle ayırt edilir; JVM biçimli imzalar (`(Ljava/lang/String;I)V`) da okunur. Constructor'lar `<init>` veya sınıf adıyla, static bloklar `<clinit>` ile aranır.

**Sonuç** `match_status` sütununa yazılır:

| Değer | Anlamı |
|---|---|
| `MATCHED` | Sınıf ve metot bulundu |
| `METHOD_AMBIGUOUS` | Aynı adda birden fazla metot ayırt edilemedi, en küçük id seçildi |
| `METHOD_NOT_FOUND` | Sınıf bulundu, metot bulunamadı |
| `NO_METHOD` | Kullanım bir alan tanımında, metot yok |
| `CLASS_AMBIGUOUS` | Aynı FQCN birden fazla kayıtta; `db.class.filter` ile daraltın |
| `CLASS_NOT_FOUND` | Sınıf envanterde yok |

Nedeni `match_note` sütununda açıklanır.

### Kullanım tipleri

Her desenin `pattern.N.type` değeri `usage_type_code` olarak yazılır. Verilmezse desen adından üretilir. Komut satırında `--pattern "KOD0_SERBEST_METIN=0,STRING,*"` biçimiyle de verilebilir. Doğru kullanımları saymak için bir desen tanımlayıp `pattern.N.legacy=false` yaparsanız, `is_legacy` sütunu sayesinde raporlarda eski kullanımlardan ayrılır.

## Ekran, region, Jasper rapor ve process envanteri

`--ebml` ile tarayıcı, repolardaki ekran ve rapor tanım dosyalarını da bulup proje bazında tablolara yazar:

| Tablo | Dosyalar |
|---|---|
| `env.all_screens` | `ebml.page` paketi (ve alt paketleri) altındaki `.ebml` dosyaları |
| `env.all_popups` | `ebml.popup` paketi (ve alt paketleri) altındaki `.ebml` dosyaları |
| `env.all_regions` | Adı `RG_` ile başlayan **veya** `ebml.region` paketi altındaki `.ebml` dosyaları |
| `env.all_reports` | `ebml.report` paketi (ve alt paketleri) altındaki `.dsxml` dosyaları |
| `env.all_processes` | `process` klasörü altındaki `250001-RISM.par` veya `250001-RISM` gibi klasörlerde bulunan `processdefinition.xml` dosyaları (`.par` eki zorunlu değil) |

Region kuralı önce uygulanır: `ebml.page` veya `ebml.popup` altında olup adı `RG_` ile başlayan bir dosya region sayılır. `all_regions.match_rule` sütunu dosyanın hangi kuralla bulunduğunu (`PREFIX`, `PACKAGE`, `PREFIX+PACKAGE`) gösterir. Kurallara uymayan `.ebml` / `.dsxml` dosyaları tablolara yazılmaz, Excel'deki "EBML Dosyaları" sayfasında "Sınıflandırılmadı" olarak listelenir. `target`, `bin`, `classes` gibi derleme klasörlerindeki kopyalar sayılmaz (`scan.exclude.dirs`).

Process'lerde `process_id` klasör adının başındaki numaradan (`250001-RISM.par` veya `250001-RISM` → `250001`), `process_name` ise `processdefinition.xml` içindeki `label` özelliğinden (`label="Müşteri Değerlendirme"`) alınır. Önce kök elemanın `label` değerine bakılır, yoksa adında `process` geçen ilk elemanınkine. Klasör adı `folder_name` sütununa yazılır. `label` bulunamazsa `process_name` boş kalır ve dosya "Tarama Hataları" sayfasında listelenir. Klasör adı `ebml.process.dir` ile değiştirilebilir (varsayılan `process`); bu klasörün altında olmayan veya `<numara>[-<ad>][.par]` biçimine uymayan klasörlerdeki `processdefinition.xml` dosyaları "Sınıflandırılmadı" olarak sadece rapora yazılır. Klasör adının sonundaki `.par` eki zorunlu değildir: `250001-RISM.par` ve `250001-RISM` ikisi de process sayılır; Excel'deki "Kural" sütununda `.par` eklilerde `PAR`, eksizlerde `FOLDER` yazar.

### Kurulum ve çalıştırma

Tablolar (`env.all_screens`, `env.all_popups`, `env.all_regions`, `env.all_reports`, `env.all_processes` ve `env.v_ebml_inventory_latest` görünümü) `sql/exception_scanner_postgres.sql` ile oluşur. Exception tablolarıyla aynı tarama kaydı tablosunu (`exception_scan_run`) paylaşırlar; ekran, popup, region, rapor ve process sayıları bu tablonun sütunlarındadır.

```bash
# Sadece ekran/rapor envanteri
java -jar target/system-code-analyzer-1.0.0.jar scanner.properties --ebml --db-dry-run
java -jar target/system-code-analyzer-1.0.0.jar scanner.properties --ebml --db

# Exception taramasıyla birlikte (tek scan_run_id altında)
java -jar target/system-code-analyzer-1.0.0.jar scanner.properties --class tr.com.cs.aurora.auroracore.utility.CSException --ebml --db
```

### Proje eşleştirmesi

Her dosyanın ana proje adı `env.project.project_name` sütununda aranır ve bulunan `id` değeri `project_id` sütununa (FK) yazılır. Arama varsayılan olarak büyük/küçük harf duyarsızdır. Hangi adın aranacağını `ebml.project.name.from` belirler:

| Değer | Aranan ad |
|---|---|
| `REPO` (varsayılan) | Bitbucket repo adı; bulunamazsa repo slug'ı |
| `REPO_SLUG` | Bitbucket repo slug'ı |
| `MODULE` | Dosyanın bulunduğu modül klasörünün adı (`pom.xml`, `build.xml` veya `.project` olan klasör) |
| `BITBUCKET_PROJECT` | Bitbucket proje adı; bulunamazsa proje anahtarı |

Sonuç `project_match` sütununa yazılır: `MATCHED`, `PROJECT_AMBIGUOUS` (aynı adda birden fazla proje, en küçük id seçildi) veya `PROJECT_NOT_FOUND` (`project_id` boş). Aranan ad her durumda `project_name` sütununda durur. İlk denemede `--db-dry-run` ile çalıştırıp Excel'deki "Proje Eşleşmesi" sütununa bakarak doğru ayarı bulabilirsiniz.

### Excel raporundaki sayılar

`--ebml` ile üretilen Excel raporunda:

- **Özet** sayfasında ekran, popup, region, Jasper rapor ve process sayılarının toplamı ile tür bazında dağılımı bulunur. Altındaki repo tablosunda her repo, bağlı olduğu proje ile birlikte ve bu beş sayı ayrı sütunlarda olacak şekilde listelenir.
- **EBML Dosyaları** sayfasında hangi dosyanın nerede bulunduğu dosya bazında yer alır.

### Mevcut tablolarda project_id güncellemesi

`--ebml --db` ile çalıştırıldığında, taramada projesi bulunan dosyaların `project_id` değeri mevcut tablolara da yazılır:

| Tablo | Eşleştirme |
|---|---|
| `env.screen` (`page_type = 'page'`) | Ekranlar: `name` = `EKRAN001.ebml` |
| `env.screen` (`page_type = 'region'`) | Region'lar: `name` = `RG_Adres.ebml` |
| `env.popup` | `popup_name` = `pp_deneme` |
| `env.report` | `report_name` = rapor adı (`.dsxml` uzantılı ve uzantısız denenir) |
| `env.process` | `no` = klasördeki numara (`250001`) veya `processdefinition.xml` içindeki `name`; bulunamazsa `name` = klasör adında `-` işaretinden sonraki kısım (`RISM`) |

Karşılaştırmalar büyük/küçük harfe ve baştaki/sondaki boşluklara duyarsızdır. Aynı ad farklı projelerde bulunduysa hangi projeye ait olduğu bilinemeyeceği için o kayıt güncellenmez ve konsolda sayısı yazılır. Varsayılan olarak `project_id` değeri farklı olan satırlar güncellenir; `ebml.update.only.empty=true` ile sadece boş olanlar doldurulur. Tablo veya sütun bulunamazsa sadece o tablo atlanır, tarama kaydı yine yapılır. Güncelleme envanter kayıtlarıyla aynı transaction'da yapılır; `--db-dry-run` modunda yapılmaz. Kapatmak için `ebml.update.existing=false`; tablo ve sütun adları `db.existing.*` ayarlarıyla değiştirilebilir.

Her kayıtta dosya adı, paket, repo içindeki yol, Bitbucket'ta dosyayı açan bağlantı (taranan commit'e sabitlenmiş) ve kayıt tarihi (`created_at`) bulunur. Son taramanın proje bazlı sayıları için `env.v_ebml_inventory_latest` görünümü kullanılabilir.

## REST API ve Swagger

Tarayıcı komut satırı yerine bir web servisi olarak da çalışabilir. Taramalar tarayıcıdan, Swagger arayüzüyle başlatılır ve raporlar oradan indirilir:

```bash
java -jar target/system-code-analyzer-1.0.0.jar --server
```

Geliştirirken yerelde başlatmanın üç yolu vardır; hepsi `http://localhost:8080/swagger-ui.html` adresini açar:

- IDE'de `ScanServerApplication` sınıfını çalıştırın (IntelliJ: sınıfın yanındaki ▶ veya Spring Boot çalıştırma ayarı). `ScannerApp` sınıfını parametresiz çalıştırırsanız sunucu değil komut satırı taraması başlar.
- `mvn spring-boot:run`
- `java -jar target/system-code-analyzer-1.0.0.jar --server`

Çalışma klasöründeki `scanner.properties` temel ayar olarak okunur. Bitbucket token'ı için `BITBUCKET_TOKEN` ortam değişkenini verin. Port doluysa `PORT=8081` ile değiştirin.

| Adres | Açıklama |
|---|---|
| `/swagger-ui.html` (veya `/`) | Swagger arayüzü |
| `/v3/api-docs` | OpenAPI dokümanı |
| `POST /api/scans` | Tarama başlatır, hemen tarama numarasıyla döner (202) |
| `GET /api/scans`, `GET /api/scans/{id}` | Taramalar ve durumu: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` |
| `GET /api/scans/{id}/log` | Tarama logu (sürerken de okunabilir, `?tail=50`) |
| `GET /api/scans/{id}/report` | Ana Excel raporu |
| `GET /api/scans/{id}/files`, `.../files/download?path=...` | Proje bazlı raporlar ve tarama logu |
| `DELETE /api/scans/{id}` | Sıradaki taramayı iptal eder veya biten taramayı raporlarıyla siler |
| `GET /api/reports/repos` | En son taramadaki repolar ve sayıları; `?project=A` ile tek proje, `&scanRunId=42` ile belirli bir tarama. Veritabanından |
| `GET /api/reports/excel` | Excel raporu: parametresiz en son taramanın tamamı, `?project=A` bir proje, `?project=A&repo=A3` tek repo. Rapor istendiği anda veritabanından üretilir, bkz. [Veritabanından rapor](#veritabanından-rapor) |
| `GET /api/settings` | Sunucudaki temel ayarlar (token ve şifreler maskeli) |
| `/actuator/health/liveness`, `/actuator/health/readiness` | Sağlık kontrolleri |

İstek gövdesi komut satırı parametrelerinin karşılığıdır. Boş bırakılan alanlarda sunucudaki ayar dosyası geçerlidir; `{}` ile gönderilirse doğrudan ayar dosyasındaki değerlerle taranır:

```json
{
  "projects": ["PRJ"],
  "includeRepos": ["PRJ/repo1", "PRJ/repo2"],
  "excludeRepos": ["z_atil_*", "nova-*"],
  "branch": "develop",
  "classes": ["com.firma.framework.CustomException"],
  "patterns": ["0,STRING", "0,STRING,*"],
  "properties": { "git.timeout.minutes": "30" }
}
```

**Tarama numarası:** `db.enabled=true` ise tarama sıraya alınırken `env.exception_scan_run` kaydı açılır ve tarama numarası bu kaydın id'si olur (ör. `42`); `GET /api/scans/42`, rapor klasörü `42/`, tablolardaki `scan_run_id = 42` ve `/api/reports/excel?scanRunId=42` aynı taramayı gösterir. Kayıt tarama bitince doldurulur; o zamana kadar `finished_at` boştur ve `v_*_latest` görünümleri onu saymaz. Tarama veritabanına yazılmadan biterse (sıradayken iptal, hata, sunucunun tarama sırasında yeniden başlaması, `db.dry.run=true`) bu boş kayıt silinir. Veritabanı kapalıysa veya kayıt açılamazsa numara zaman damgalı verilir (ör. `20261009-142233-a1b2`) ve `scan_run_id` tarama sonunda ayrıca oluşur; her durumda tarama sonucundaki `scanRunId` alanı kaydın id'sini gösterir.

Taramalar sırayla çalışır (aynı anda tek tarama isteği); sonradan gelenler sırada bekler. Bir taramanın içindeki projeler ve repolar ise paralel işlenir (bkz. [Paralel işleme](#paralel-işleme)). Klonlanan repolar taramalar arasında saklanır, sonraki taramada sadece değişiklikler çekilir. Her taramanın raporları, logu ve durumu `<rapor klasörü>/<tarama numarası>/` altına yazılır; sunucu yeniden başladığında önceki taramalar listede kalır.

### Veritabanından rapor

Excel raporu tarama bittikten sonra, istendiği anda veritabanındaki kayıtlardan üretilir. `db.enabled=true` ile yapılmış taramalar için çalışır (otomatik tarama, Swagger veya komut satırı fark etmez).

```
GET /api/reports/excel                                  → en son taramanın tamamı (tüm projeler)
GET /api/reports/excel?project=A                        → A projesinin en son taramadaki tüm repoları
GET /api/reports/excel?project=A&repo=A3                → A3'ün başarıyla tarandığı en son tarama
GET /api/reports/excel?scanRunId=42                     → belirli bir taramanın tamamı (env.exception_scan_run.id)
GET /api/reports/excel?project=A&repo=A3&scanRunId=42   → belirli bir taramada tek repo
GET /api/reports/repos?project=A                        → ekranda listelemek için repolar ve sayıları
```

Rapor veritabanından alındığı için sunucuda tarama sonunda Excel yazmaya gerek yoktur; sunucunun ayar dosyasında `report.excel=false` önerilir (tarama kısalır, diskte dosya birikmez). Bu durumda `GET /api/scans/{id}/report` 404 döner. Veritabanına yazma hata verirse (kayıt tek transaction'dadır) o tarama için rapor alınamaz; hata tarama logunda görünür.

Excel'in biçimi tarama sonundaki raporla aynıdır (Kullanımlar, Özet, Mesajlar, Yutulan Exception'lar, Tarama Hataları). Farklar:

- "Alt Sınıflar" ve EBML sayfaları yoktur; türeyen exception sınıflarının sayıları Özet'te `custom_exception_class_count`'tan gösterilir.
- Kullanımlar'daki "Mesaj Tipi" ve Yutulan Exception'lar'daki "Modül" sütunları boştur (tabloda tutulmuyor; modül dosya yolunun başında görünür).
- `db.insert.unmatched=false` ile atlanan kayıtlar raporda da yoktur.

Gereken: `env.exception_scan_repo` tablosu. Mevcut veritabanında SQL dosyasının sonundaki `CREATE TABLE env.exception_scan_repo` bloğuyla ekleyin. Tablo yoksa tarama yine yazılır, logda uyarı verilir; tablo eklenmeden önce yapılmış taramaların raporu alınamaz.

### Günlük otomatik tarama

Sunucu ayakta olduğu sürece günde bir kez, sunucudaki `scanner.properties` ile kendiliğinden tarar (Swagger'dan `{}` gönderilmiş gibi). Varsayılan zaman her gün 12:30'dur (İstanbul saati). Swagger'da `GET /api/schedule` bir sonraki çalışma zamanını gösterir. Otomatik taramalar listede `"trigger": "SCHEDULE"`, Swagger/REST ile başlatılanlar `"API"` olarak görünür. Bunların dışında istediğiniz zaman `POST /api/scans` ile tarama başlatabilirsiniz.

Pod planlanan saatte kapalıysa (yeniden dağıtım, node bakımı vb.) o günün taraması kaçmaz: açılışta, son planlanan zamandan sonra otomatik tarama yapılmadığı görülürse hemen başlatılır. Önceki otomatik tarama hâlâ sürüyorsa yenisi başlatılmaz.

Otomatik tarama, sınıf/metot envanterini dolduran deep-scanner'ın güncel olmasını bekler. Başlamadan önce `env.scan_run` tablosunda `status = 'SUCCESS'` olan ve `finished_at` değeri son `db.deep.scan.max.age.days` gün (varsayılan 1) içinde kalan bir kayıt aranır. Böyle bir kayıt yoksa tarama yapılmaz ve logda `Deep-scanner taraması güncel olmadığı için hata taraması pas geçildi` yazar (son başarılı çalışmanın zamanıyla). Veritabanına bağlanılamazsa ya da `db.url` boşsa da tarama pas geçilir. Pas geçilen tarama o gün tekrar denenmez, ertesi gün 12:30'da yeniden denenir; pod yeniden başlarsa kaçırılmış tarama gibi yeniden denenir. Kontrol için `db.url` yeterlidir, `db.enabled` gerekmez. `db.deep.scan.max.age.days=0` kontrolü kapatır. Tablo, sütun ve durum değeri `db.deep.scan.table`, `db.deep.scan.status.column`, `db.deep.scan.finished.column` ve `db.deep.scan.success.status` ile değiştirilebilir. Swagger'dan elle başlatılan taramalar kontrol edilmez.

Zamanı değiştirmek için `SCANNER_SCHEDULE_CRON` kullanılır. Format Spring cron'dur: `saniye dakika saat gün ay haftanın-günü`. Örneğin `0 30 12 * * *` her gün 12:30, `0 30 1 * * MON` her Pazartesi 01:30 demektir. `-` verilirse otomatik tarama kapanır.

Sunucu ayarları ortam değişkenleriyle verilir:

| Değişken | Varsayılan | Açıklama |
|---|---|---|
| `PORT` | `8080` | |
| `SCANNER_CONFIG` | `scanner.properties` | Temel ayar dosyası |
| `SCANNER_WORK_DIR` | `./scannedRepos` | Klonların tutulduğu klasör |
| `SCANNER_OUTPUT_DIR` | `./scanReports` | Raporların yazıldığı klasör |
| `SCANNER_SCHEDULE_CRON` | `0 30 12 * * *` | Günlük otomatik tarama zamanı; `-` kapatır |
| `SCANNER_SCHEDULE_ZONE` | `Europe/Istanbul` | Cron'un saat dilimi |
| `SCANNER_API_KEY` | boş | Doluysa `/api` istekleri `X-API-Key` başlığını ister (Swagger'da **Authorize**) |
| `EXTRA_CA_BUNDLE` | boş | Şirket CA sertifikaları (PEM); Java'ya ve git'e eklenir |
| `BITBUCKET_USERNAME`, `BITBUCKET_TOKEN`, `DB_USER`, `DB_PASSWORD` | | Komut satırındaki gibi |

## Docker

`Dockerfile` jar'ı derleyip sunucu modunda çalıştıran bir imaj üretir; içinde git vardır. Ayar olarak depodaki `scanner.properties` imaja kopyalanır, klonlar `/data/work`, raporlar `/data/reports` altına yazılır.

```bash
docker build -t system-code-analyzer .
docker run -d -p 8080:8080 -v system-code-analyzer-data:/data \
  -e BITBUCKET_TOKEN=<token> -e SCANNER_API_KEY=<rastgele-uzun-değer> \
  -v "$PWD/scanner.properties:/app/scanner.properties" \
  system-code-analyzer
# Swagger: http://localhost:8080/swagger-ui.html
```

Heap konteyner bellek limitinin %75'i olarak ayarlanır. Bitbucket'ın sertifikası tanınmıyorsa PEM dosyasını bağlayıp `EXTRA_CA_BUNDLE` (Java için) ve `GIT_SSL_CAINFO` (git için) ile verin (git bu dosyayı sistem sertifikalarının yerine kullanır; sistem paketiyle birleştirilmiş bir dosya verin).

## Kod karmaşıklığı analizi

Exception taramasından sonra klonlanmış her repoda metot karmaşıklığı ve kalite analizi yapılabilir: dallanma (CC), cognitive, kod satırı, iç içe derinlik, NPath ve PMD ihlalleri. Skor `health-score-catalog.yaml` içindedir; karmaşıklık ve kalite ayrıdır. Analizör `com.scanner.complexity` paketindedir. Bağlantıyı `ComplexityRunner` sınıfı kurar; PMD ruleset'i `src/main/resources/config/pmd-ruleset-complexity.xml` dosyasındadır.

`complexity.enabled=true` ya da `--complexity` ile açılır. Tarama nereden başlatılırsa başlatılsın (komut satırı, Swagger / REST, günlük otomatik tarama) analizin yapılıp yapılmayacağını bu tek ayar belirler; Swagger'dan bir defalığına `"properties": {"complexity.enabled": "true"}` ile de açılabilir. `exception.classes` boşsa sadece karmaşıklık analizi yapılır.

**Şu an:** karmaşıklık yalnızca PMD (`complexity.engine=pmd`). Skor `health-score-catalog.yaml`: karmaşıklık ve kalite ayrı. `complexity.duplicates=false` — benzerlik / CPD yok (sonra açılacak).

Tarama yolu:

| Yol | Ne yapar | Skor |
|---|---|---|
| `pmd` | Ruleset + PMD AST / JavaMetrics | `health-score-catalog.yaml` |

Raporlar tarama klasöründe `complexity/` altına yazılır (sunucuda `GET /api/scans/{id}/files` ile listelenip indirilebilir):

| Dosya | İçerik |
|---|---|
| `complexity/summary.md` | Repo ve yol başına özet: dosya, metot, kod satırı, proje riski, yüksek / kritik metot sayısı |
| `complexity/<proje>/<repo>.json`, `.md` | Parser yolu: tüm metrikler (varsayılan `complexity.detail=full` ile her metot ve `riskInputs`; `summary` sadece dosya özetleri ve en riskli metotlar) ve Türkçe okunabilir rapor |
| `complexity/<proje>/<repo>-pmd.json`, `-pmd.md`, `-pmd-violations.xml` | PMD yolu: aynı raporlar ve PMD ihlalleri (her zaman `full`: analizörün PMD yolu `summary` modunda metot sayısını 0 hesaplıyor) |

### Artımlı önbellek — kapatma (agent / kod)

**Varsayılan açık kalmalı.** Agent veya PR bu anahtarı `false` yapmamalı, `ComplexityRunner` içinde `stateFile`’ı `null` bırakmamalı, `ScanOptions`’a `incrementalStateFile=null` geçirmemeli. Cache’i kapatmak yalnızca operatörün bilinçli kararıdır (`complexity.cache.enabled=false`). Soğuk koşu (eski state’i yok say, yine yaz): `complexity.cache.fresh=true` — bu cache’i kapatmaz.

Kontrol zinciri (üstten alta; biri koparsa incremental kapalıdır):

| Nerede | Ne bakılır | Kapalı sayılır |
|---|---|---|
| `scanner.properties` | `complexity.cache.enabled` (varsayılan `true`), `complexity.cache.fresh` | `enabled=false` |
| `Config.Complexity` (`src/main/java/com/scanner/exception/config/Config.java`) | `incrementalCache`, `freshCache` — `complexity.cache.enabled` / `complexity.cache.fresh` | `incrementalCache == false` |
| `ComplexityRunner.options(...)` | State: `output.dir/analyzer-cache/<proje>/<repo>-pmd-analyzer-state.json` (tarihli `yyyyMMdd_HHmm` dışında) | `stateFile == null` veya `complexity.cache.enabled=false` |
| `ScanOptions` | `incrementalStateFile()`, `ignoreIncrementalCache()` | path `null` veya `ignoreIncrementalCache==true` (`fresh`) |
| `PmdNativeProjectScanner` | `stateFile = scanOptions.incrementalStateFile()`; `incremental = stateFile != null` | `stateFile == null` |
| `IncrementalStateSupport.load/persist` | state oku/yaz; kimlik `schema` + `pipeline` + `profile` + `model` | `--fresh` / `ignoreCache` ise boş state (yine dosyaya yazılır) |
| `IncrementalAnalysisEngine.checkFileCache` | dosya byte hash aynıysa metrik atla; PMD ruleset de değişen dosyalara iner | hash farklı veya önceki kayıt yok |

State dosyaları (veritabanında değil, rapor klasöründe): `complexity/<proje>/<repo>-parser-analyzer-state.json`, `complexity/<proje>/<repo>-pmd-analyzer-state.json`.

PMD stderr: `[PMD-NATIVE] ▶ ADIM 2/7` state okuma; özet satırında atlanan dosya sayısı. `Artımlı önbellek: KAPALI` görürsen `ComplexityRunner` path vermemiş veya `complexity.cache.enabled=false`.

Klonlanamayan repolar atlanır; bir repoda analiz hata verirse diğerleri devam eder. "Java heap space" hatasında `complexity.duplicates.near=false` ve `complexity.workers=2` deneyin; yetmezse pod bellekini artırın. Karmaşıklık analizi başlamadan exception bulguları bellekten bırakılır. Test kaynakları varsayılan olarak hariçtir (`complexity.exclude=**/src/test/**`).

### Kısa metotlar

Skor `health-score-catalog.yaml`. Satır sayısına göre ayrı bir tavan uygulanmaz. Trivial get/set/is hem karmaşıklık hem kalite hesabından çıkar. JSON ve veritabanı, karmaşıklığı 30’un altındaki metotları yazmaz (`db.method.risk.min.score`). 1–2 fiziksel satırlık get/set/is için `JavaMetrics` üretilmez.

### PMD metrik hataları (stack / heap) ve JVM

Bazı dosyalarda PMD metrik derinliği JVM **thread stack** veya **heap** sınırına takılabilir (`StackOverflowError` / `OutOfMemoryError`). Tarayıcı artık **tüm repoyu düşürmez**: sorunlu metotlar için `errors[]` içinde **dosya + sınıf + imza + satır** kaydı oluşur; aynı metotta ölçüm yarım kaldıysa rapora **nominal yüksek metriklerle** (en az **CRITICAL** bandı, skor ≥ 80) bir satır yazılır — `riskFactors` içinde **`PMD_ANALYSIS_INCOMPLETE`** ifadesi bulunur; gerçek CC/LOC değildir, **manuel inceleme** anlamına gelir.

Her metot için `VirtualMachineError` yakalanırsa **aynı dosyadaki diğer metotlara devam** edilir; yalnızca o metotta **nominal metrik + `riskScore` = 100** ve JSON’da **`metricsImputed`: true** yazılır (`riskFactors` içinde `METRICS_IMPUTED=true`). Tüm birim parse’ı veya dosya okuma burada koparsa hâlâ **dosya düzeyi** `errors[]` kalır.

Operasyonel olarak:

- **`java -Xss2m`** veya **`java -Xss4m`** (dar stack; özellikle Windows’ta jar çift tıklama varsayılanları) — çoğu SOE azalır.
- **`JAVA_OPTS` / `MAVEN_OPTS`**: **` -Xmx…`** heap yetersizse.
- **`PMD_JAVA_DISABLE_AUX_CLASSPATH_WARNINGS=true`** — classpath uyarı flood’unu kapatır (tip çözümlemesi için asıl çözüm: projeye uygun **aux classpath** / derlenmiş sınıflar).

Projede gömülü PMD sürümü **`pom.xml`** içindeki **`pmd.version`** ile sabittir; güncel sürüm notları [PMD releases](https://github.com/pmd/pmd/releases).

### Veritabanına ve JSON’a kayıt

`env.method_risk_analyze` ve analiz JSON’undaki `files[].classes[].methods` **yazılmaz** eğer:

- `risk_score < db.method.risk.min.score` (varsayılan **30**)
- veya metot basit getter/setter (`MethodAccessorFilter`)

`summary.methodCount` hâlâ tüm metotları sayar. 3+ satırlık `getCustomer()` gibi iş yapan metotlar ölçülür; skor ≥ 30 ise yazılır.

`db.enabled=true` ise kalan satırlar exception taramasının kaydına (`env.exception_scan_run.id`) bağlı olarak iki tabloya da yazılır; her repo kendi transaction'ında, analizi biter bitmez:

| Tablo | Satır | Alanlar |
|---|---|---|
| `env.method_risk_analyze` (`db.method.risk.table`) | Her metot, her yol için bir satır | `method_uid` (`yol#sınıf#imza#satır`), `file_path`, `class_name`, `method_signature`, `start_line`, `end_line`, `is_parser` / `is_pmd`, `ncss_loc` (yorum ve boş satır hariç kod satırı), `risk_score`, `risk_level`, `risk_inputs_json` (JSON rapordaki `riskInputs`: profil, metrikler, risk faktörleri, puan dağılımı), `body_hash`, `structural_hash`, `java_method_id` |
| `env.method_similarity` (`db.method.similarity.table`) | Her kopya / benzer kod grubu, her yol için bir satır | `group_key` (`BIREBIR-1`, `BENZER-1` ...), `is_parser` / `is_pmd`, `similarity_type`, `similarity_percent`, `shared_ncss_lines`, `members_json` (üye metotlar: dosya, sınıf, metot, satırlar), `meta_json` (grup no, Türkçe etiket, token sayısı, yol) |

Metotlar ve gruplar ayrı transaction'larda yazılır; birinin hatası diğerini geri almaz. Benzerliği `db.method.similarity.min.percent`'ten (varsayılan 51, tablodaki `similarity_percent > 50` kısıtıyla aynı) düşük gruplar yazılmaz. Tablodaki başka bir CHECK kısıtına uymayan grup da atlanır, diğerleri yazılır; logda kısıtın adı ve atlanan grup sayısı görünür.

`java_method_id`, exception kullanımlarındaki gibi `env.java_class` / `env.java_method` ile eşleştirilir (sınıfın tam adı, metot adı ve parametre tipleri); bulunamazsa boş kalır. Kopya kod grubu ancak paylaşılan kod en az 40 satırsa raporlanır (analizörün kuralı).

Gereken: `scan_run_id` sütunu `bigint` olmalı; `sql/method_risk_tables_migration.sql` bunu yapar (tablolardaki eski kayıtları siler). `scan_run_id` hâlâ `uuid` ise o tabloya yazılmaz, logda uyarı çıkar. Tablolarda proje / repo sütunu yoktur; aynı taramadaki farklı repoların aynı yoldaki dosyaları ayırt edilemez (`risk_inputs_json` / `meta_json` içindeki `analyzedPath` klonun yolunu gösterir). İleride `project_key` ve `repo` sütunları eklenirse tarayıcı onları da doldurur. Tarama veritabanına yazılmadıysa (`db.enabled=false`, `--db-dry-run` veya yazma hatası) karmaşıklık sonuçları sadece dosyalara yazılır.

## Paralel işleme

Çok sayıda proje ve repo tarandığında süre büyük ölçüde repoların çekilmesine (klonlama / güncelleme) gider. Bu yüzden:

- `bitbucket.projects=A,B,C,D,E` gibi birden fazla proje verildiğinde projelerin repo listeleri Bitbucket'tan paralel alınır.
- Repolar projeler arasında sırayla dağıtılır (A1, B1, C1, D1, E1, A2, ...). Böylece repo sayısı çok olan bir proje diğerlerini bekletmez, beş proje aynı anda ilerler.
- Klonlama ve ayrıştırma ayrı işçi havuzlarında çalışır. `git.threads` (varsayılan 8) aynı anda klonlanan/güncellenen repo sayısıdır; ağ işi olduğu için yüksek tutulabilir. `threads` (varsayılan 4) aynı anda ayrıştırılan repo sayısıdır; işlemci ve bellek kullandığı için düşük tutulur. Bir repo klonlanır klonlanmaz ayrıştırmaya geçer, bu sırada diğer repolar çekilmeye devam eder.

Büyük repolar da kendi içinde paralel taranır: bir reponun Java dosyaları işlemci çekirdekleri arasında paylaştırılarak ayrıştırılır. Bu yüzden binlerce dosyalı tek bir repo, diğer repolar bittikten sonra tek başına uzun süre çalışmaz. Aranan exception'ın alt sınıfları bulunurken de tüm dosyalar değil, sadece o exception'ın (veya bulunan alt sınıflarının) adını içeren dosyalar ayrıştırılır.

Dosya ayrıştırma zaten tüm çekirdekleri kullandığı için `threads` değerini çekirdek sayısından çok yüksek vermek taramayı hızlandırmaz; sadece bellek kullanımını artırır. Çekirdek sayısı kadar (konteynerde CPU limiti kadar) vermeniz yeterlidir. `git.threads` ağ işi olduğu için daha yüksek tutulabilir.

Repolar `work.dir` altında saklandığı için ilk taramadan sonraki taramalarda sadece değişiklikler çekilir ve tarama çok daha hızlı biter. `git.threads` değerini Bitbucket sunucusunu zorlamayacak kadar tutun.

### Sadece gerekli dosyaların indirilmesi

Büyük repolarda sürenin çoğu, taramada hiç okunmayan jar, resim, doküman ve binary dosyaların indirilmesine gider. `git.sparse=true` yapılırsa sadece tarayıcının okuduğu dosyalar indirilir (varsayılan `false`, yani repolar tam klonlanır):

- `.java` dosyaları
- modül adı için `pom.xml`, `build.gradle`, `build.gradle.kts`, `build.xml`, `.project`
- EBML envanteri açıksa `.ebml`, `.dsxml` ve `processdefinition.xml`

Bunun için git'in partial clone (`--filter=blob:none`) ve sparse checkout özellikleri kullanılır. Git sunucusu partial clone desteklemiyorsa git normal klonlamaya döner ve logda bir kez uyarı çıkar; bu durumda indirme süresi değişmez, sadece diske yazılan dosyalar azalır. Desteğin olup olmadığı Bitbucket sürümüne ve sunucu ayarlarına bağlıdır; ilk taramanın logunda bu uyarı yoksa partial clone çalışıyor demektir.

Her repo için log satırında klonlama ve tarama süresi yazar (`klon 42 sn, tarama 3 sn`), tarama sonunda da toplamlar ve en uzun süren repo gösterilir. Böylece sürenin nereye gittiği görülebilir. `git.sparse` değiştirildiğinde var olan klonlar bir sonraki taramada yeni ayara uyarlanır (tam klondan sparse'a veya tersi).

## Bellek kullanımı

Tarayıcı her dosyayı ayrıştırıp işini bitirince bellekten bırakır. Kullanım içermesi mümkün olmayan dosyaları, metin olarak aranan sınıf veya metot adını içermiyorsa hiç ayrıştırmaz. Excel raporu da satır satır diske yazılır. Bu sayede bellek kullanımı repo boyutundan büyük ölçüde bağımsızdır.

Yine de `OutOfMemoryError` alırsanız:

```bash
java -Xmx4g -jar target/system-code-analyzer-1.0.0.jar scanner.properties --threads 2
```

`-Xmx4g` Java'ya 4 GB bellek kullanma izni verir; `--threads` aynı anda taranan repo sayısını azaltır. Tek bir repo belleği aşarsa tarama durmaz: o repo raporda "OUT_OF_MEMORY" hatasıyla işaretlenir, diğerleri taranmaya devam eder. `scan.max.file.kb` (varsayılan 2048 KB) üzerindeki dosyalar, genelde üretilmiş kod oldukları için atlanır ve Tarama Hataları sayfasında "SKIPPED" olarak listelenir.

## Sınırlamalar

Tarama derleme classpath'i olmadan yapıldığı için tip çözümlemesi import'lara ve paket adına bakılarak yapılır. Aynı isimli başka bir sınıfın `*` ile import edildiği nadir durumlarda kullanım kaçabilir; şüphe varsa `match.lenient=true` ile sadece sınıf adına göre eşleştirme yapılabilir (bu durumda aynı isimli farklı sınıflar da raporlanabilir).

Sadece `.java` dosyaları taranır; Kotlin (`.kt`) dosyaları ve JSP scriptlet'leri içindeki kullanımlar rapora girmez. Java 6'dan en yeni sürümlere kadar Java kodu okunabilir.

Hiç sonuç çıkmazsa tarayıcı, konsolun sonunda nedenini açıklar: hiç Java dosyası bulunamaması, sınıf adının hiçbir dosyada geçmemesi, paket adının uyuşmaması veya desenin kullanımların argüman sayısına uymaması gibi.

Mesajı bir değişkenden gelen kullanımlarda (`VAR`) mesajın ne olduğu çalışma zamanında belli olur, bunlar kod okunarak incelenmelidir. Aynı şekilde id bir değişken veya sabitten geliyorsa (`new CustomException(SIFIR, "...")`) `0` deseni bunu yakalamaz; gerekirse `CONST,STRING` gibi ek bir desen tanımlayın.

Dosyalar önce UTF-8 olarak okunur; geçersiz karakter varsa `scan.fallback.charset` (varsayılan windows-1254) kullanılır. Eski projelerdeki Türkçe karakterler bozuk görünürse bu ayarı ISO-8859-9 olarak deneyin.

## Kod yapısı

Kaynaklar `src/main/java/com/scanner/exception` altında, sorumluluğa göre paketlere ayrılmıştır:

| Paket | İçerik |
|---|---|
| (kök) | `ScannerApp`: komut satırı girişi (jar'ın ana sınıfı) ve tarama akışı |
| `config` | `Config`: `scanner.properties` ve komut satırı değerlerinin okunması |
| `model` | Repo bilgisi, tarama sonucu ve bulgular: `RepoInfo`, `RepoResult`, `UsageFinding`, `CatchFinding`, `SubclassFinding`, `EbmlFile`, `ScanError` ... |
| `repo` | Repo listesini alma ve klonlama: `BitbucketClient`, `GitUrlRepos`, `LocalRepos`, `GitRunner` |
| `scan` | Java ve EBML dosyalarının taranması, desen eşleştirme: `JavaSourceScanner`, `EbmlScanner`, `UsagePattern`, `ArgumentClassifier`, `TypeMatcher` ... |
| `db` | PostgreSQL envanteriyle eşleştirme ve kayıt: `DbMatcher`, `PostgresExporter` |
| `report` | Excel raporu: `ExcelReportWriter` |
| `server` | REST API ve Swagger (Spring Boot): `ScanServerApplication`, `ScanController`, `ScanJobService`, günlük tarama ... |
