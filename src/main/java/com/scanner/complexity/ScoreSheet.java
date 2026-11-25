package com.scanner.complexity;

import java.util.HashMap;
import java.util.Map;

/** Ekranın hesap yapmadan basacağı ad ve puan yazıları. */
final class ScoreSheet {

    private static final Map<String, String> LABELS = new HashMap<>();
    private static final Map<String, String> QUALITY_NOTES = new HashMap<>();

    static {
        label("branching", "Karar sayısı");
        label("cognitive", "Anlaması zor yapı (Cognitive Complexity)");
        label("npath", "Olası yol sayısı");
        label("length", "Metod uzunluğu");
        label("parameters", "Parametre sayısı");
        label("nesting", "İç içe derinlik");
        label("demeter", "Zincirleme çağrı");
        label("collapsible_if", "Birleşebilir if");
        label("boolean_noise", "Gereksiz boolean");
        label("generic_catch", "Genel Exception yakalama");
        label("missing_braces", "Eksik süslü parantez");
        label("literal_first", "Boşta kırılan karşılaştırma");
        label("useless_parentheses", "Gereksiz parantez");
        label("confusing_ternary", "Karışık üçlü ifade");
        label("magic_condition", "Koşulda sabit sayı");
        label("alloc_in_loop", "Döngü içinde nesne yaratma");
        label("guard_log", "Koruma kaydı");
        label("exception_flow", "Hata akışı");
        label("unused_local", "Kullanılmayan değişken");
        label("lost_stack", "Kaybolan hata izi");
        label("string_concat_loop", "Döngüde metin birleştirme");
        label("loose_coupling", "Gevşek tip");
        label("foreach", "foreach kullanımı");
        label("null_assignment", "Null atama");
        label("equals_null", "Null ile equals");
        label("null_collection", "Null dönen koleksiyon");
        label("one_declaration", "Tek satırda birden fazla tanım");
        label("system_out", "System.out");
        label("buffer_size", "Tampon boyutu");
        label("simplified_ternary", "Sadeleşebilir üçlü ifade");
        label("resource_leak", "Kapanmayan kaynak");
        label("naming", "Adlandırma");
        label("consecutive_append", "Art arda ekleme");
        label("logic_inversion", "Tersine çevrilebilir koşul");
        label("raw_throw", "Ham throw");
        label("concurrent_map", "Eşzamanlı map");
        label("swallowed_exception", "Yutulan hata");
        label("print_stack_trace", "printStackTrace");
        label("literal_appends", "Sabit metin ekleme");
        label("wrapper_new", "Gereksiz sarmalayıcı");
        label("append_char", "Karakter ekleme");
        label("unnecessary_cast", "Gereksiz cast");
        label("for_loop_variables", "Döngü değişkeni");
        label("broken_null_check", "Hatalı null kontrolü");
        label("raw_thread", "Ham thread");
        label("misplaced_null_check", "Yanlış yerde null kontrolü");
        label("useless_valueof", "Gereksiz valueOf");
        label("instanceof_catch", "instanceof ile catch");
        label("case_change", "Büyük/küçük harf");
        label("reference_equals", "Referansla equals");
        label("string_equals", "Metin karşılaştırması");
        label("rethrow", "Yeniden fırlatma");
        label("finally_return", "finally içinde return");
        label("boolean_return", "Boolean dönüş");
        label("switch_density", "Sık switch");
        label("finally_throw", "finally içinde throw");
        label("catch_throwable", "Throwable yakalama");
        label("synchronized_block", "synchronized blok");
        label("equals_hashcode", "equals ve hashCode");
        label("constructor_override", "Kurucuda ezilebilir çağrı");
        label("hardcoded_crypto", "Koda gömülü şifre anahtarı");
        label("insecure_iv", "Sabit şifre vektörü");
        label("hardcoded_ip", "Koda gömülü adres");
        label("double_checked_locking", "Çift kontrollü kilit");
        label("unsafe_singleton", "Korumasız tekil nesne");
        label("unchecked_resultset", "Bakılmayan sonuç satırı");
        label("switch_default", "Switch içinde default");
        label("duplicate_literals", "Tekrarlanan sabit metin");
        label("suspicious_equals", "Şüpheli equals adı");
        label("suspicious_hashcode", "Şüpheli hashCode adı");
        label("try_with_resources", "Kaynak kapatma kalıbı");
        label("thread_run", "run ile başlatma");
        label("thread_run_override", "Thread.run ezme");
        label("notify_all", "notify yerine notifyAll");
        label("explicit_types", "Açık tip");
        label("synchronized_method", "Metodun tamamını kilitleme");
        label("identical_catch", "Aynı catch gövdesi");
        label("reassign_catch", "Catch değişkenini değiştirme");
        label("empty_finalizer", "Boş finalize");
        label("missing_comment", "Eksik açıklama");
        label("empty_body_comment", "Boş gövde");
        label("diamond", "Elmas operatörü");
        label("method_reference", "Metod referansı");
        label("numeric_underscores", "Sayı ayıracı");
        label("array_copy", "El ile dizi kopyalama");
        label("log_format", "Log biçimi");
        label("logger_count", "Birden fazla logger");
        label("logger_declaration", "Logger tanımı");
        label("conversion_temporary", "Ara dönüşüm nesnesi");
        label("volatile_field", "volatile alan");
        label("unused_parameter", "Kullanılmayan parametre");
        label("unused_label", "Kullanılmayan etiket");
        label("unused_import", "Kullanılmayan import");
        label("unused_member", "Kullanılmayan üye");

        note("reference_equals", "İki nesne eşit mi diye kimliklerine bakılmış. İçerikleri aynı mı diye bakılmalı.");
        note("equals_hashcode", "Eşitlik yazılmış, aynı kuralın sayı karşılığı yazılmamış. İkisi birlikte durmalı.");
        note("constructor_override", "Nesne daha kurulurken, alt sınıfın değiştirebildiği bir iş çağrılmış. O iş henüz hazır olmayabilir.");
        note("null_assignment", "Bir değişken elle boşaltılmış. İş bitince kendiliğinden düşmesi daha güvenli.");
        note("equals_null", "Boş mu diye equals ile bakılmış. Bu çağrı boşsa kendisi kırılır. == ile bakılmalı.");
        note("null_collection", "Liste ya da küme boş yerine null dönmüş. Çağıran taraf boş mu diye bakmadan kullanınca kırılır.");
        note("resource_leak", "Açılan dosya ya da bağlantı kapatılmadan bırakılmış.");
        note("swallowed_exception", "Hata yakalanıp içi boş bırakılmış. Ne olduğu kaybolur.");
        note("generic_catch", "Her tür hata aynı yere toplanmış. Yalnızca beklenen hata ayrı yakalanmalı.");
        note("catch_throwable", "En geniş hata tipi yakalanmış. Ciddi sistem hataları da yutulur.");
        note("finally_throw", "İş bitişinde yeni bir hata fırlatılmış. Asıl hata bunun altında kalır.");
        note("finally_return", "İş bitişinde dönüş yapılmış. Asıl dönülecek sonucu ezer.");
        note("hardcoded_crypto", "Şifre anahtarı kodun içine yazılmış. Kaynakta durmamalı.");
        note("insecure_iv", "Şifreleme her seferinde aynı başlangıç değeriyle yapılmış. Her seferinde yeni üretilmeli.");
        note("hardcoded_ip", "Sunucu adresi kodun içine yazılmış. Ayarlardan gelmeli.");
        note("double_checked_locking", "Tek nesne iki kez kontrol edilerek kurulmuş. İşaret doğru konmazsa iki kopya çıkabilir.");
        note("unsafe_singleton", "Tek olması gereken nesne kilitsiz kurulmuş. İki iş aynı anda iki tane yaratabilir.");
        note("concurrent_map", "Birden fazla işin yazdığı harita buna uygun değil. Eşzamanlı olanı kullanılmalı.");
        note("unchecked_resultset", "Sonuç satırı var mı diye bakılmadan okunmuş. Satır yoksa kırılır.");
        note("switch_default", "Seçenek listesinin sonuna genel bir dal konmuş. Yeni bir değer sessizce oraya düşer.");
        note("duplicate_literals", "Aynı sabit yazı birçok yerde tekrarlanmış. Tek bir ada bağlanmalı.");
        note("broken_null_check", "Önce iş yapılmış, boş mu diye sonra bakılmış. Boşsa o işe hiç girilmez.");
        note("misplaced_null_check", "Değer kullanıldıktan sonra boş mu diye bakılmış. Bakış kullanımdan önce olmalı.");
        note("suspicious_equals", "Eşitlik metodunun adı yanlış yazılmış. Dil onu eşitlik saymaz, hiç çağrılmaz.");
        note("suspicious_hashcode", "Sayı karşılığı metodunun adı yanlış yazılmış. Dil onu tanımaz.");
        note("string_equals", "İki metin eşit mi diye kimliklerine bakılmış. Yazıları aynı mı diye bakılmalı.");
        note("raw_throw", "Çok genel bir hata fırlatılmış. Ne olduğu anlaşılacak özel bir tip olmalı.");
        note("print_stack_trace", "Hata yalnızca ekrana basılmış. Kayda geçmez.");
        note("try_with_resources", "Açılan kaynak elle kapatılmaya çalışılmış. Açılışta kapanacak biçimde yazılmalı.");
        note("lost_stack", "Yeni hata kurulurken eskisinin izi taşınmamış. Nereden geldiği kaybolur.");
        note("thread_run", "İş parçacığı başlatılmak yerine doğrudan çalıştırılmış. Aynı işin içinde kalır.");
        note("thread_run_override", "İş parçacığının gövdesi alt sınıfta değiştirilmiş. Yapılacak iş ayrı verilmeli.");
        note("notify_all", "Bekleyenlerden yalnız biri uyandırılmış. Hangisi olduğu belli olmaz, hepsi uyandırılmalı.");
        note("alloc_in_loop", "Döngünün her turunda yeni bir nesne kurulmuş.");
        note("string_concat_loop", "Döngüde metin her turda yeniden birleştirilmiş. Bir toplayıcıda birikmeli.");
        note("guard_log", "Kayıt kapalıyken bile mesaj metni kurulmuş. Önce kayıt açık mı diye bakılmalı.");
        note("explicit_types", "Değişkenin tipi gizlenmiş. Ne tuttuğu yazıyla görünmeli.");
        note("synchronized_method", "Metodun tamamı kilitlenmiş. Yalnızca gereken bölüm kilitlenmeli.");
        note("synchronized_block", "Nesnenin kendisi kilit olarak kullanılmış. Kilit daha dar tutulmalı.");
        note("identical_catch", "İki ayrı hata aynı işi yapıyor. Tek yerde toplanabilir.");
        note("reassign_catch", "Yakalanan hata başka bir hatayla değiştirilmiş.");
        note("instanceof_catch", "Hata yakalandıktan sonra tipi sorulmuş. Tip, yakalanırken ayrılmalı.");
        note("exception_flow", "Olmayan kayıt gibi normal bir durum hata fırlatarak anlatılmış. Dönüş yeter.");
        note("rethrow", "Hata yakalanıp aynen geri fırlatılmış. Arada bir iş yok.");
        note("empty_finalizer", "Nesne silinirken çalışan metod boş. Kapatma işi orada olmamalı.");
        note("system_out", "Mesaj kayda değil ekrana yazılmış.");
        note("magic_condition", "Koşulun içindeki sayı ne anlama geliyor belli değil. Adı olan bir sınır olmalı.");
        note("unused_member", "Sınıfın bir alanı hiç okunmuyor.");
        note("unused_local", "Bir değişken tanımlanmış, sonra hiç kullanılmamış.");
        note("loose_coupling", "Listenin somut türüne bağlanılmış. Genel liste türü yeter.");
        note("confusing_ternary", "Evet hayır seçimi ters ve dolambaçlı yazılmış. Düz hali daha kısa.");
        note("missing_braces", "Koşulun gövdesi parantezsiz. Alttaki satır yanlışlıkla koşula girer.");
        note("naming", "Sınıf ya da metod adı alışılmış büyük harf düzenine uymuyor.");
        note("foreach", "Liste yalnızca gezilmek için sayaçla dönülmüş. Eleman eleman gezmek yeter.");
        note("literal_first", "Karşılaştırma, boş olabilecek değerin üzerinden yapılmış. O değer boşsa program kırılır. Sabit metin başta olursa kırılmaz.");
        note("missing_comment", "Herkese açık işin ne yaptığı yazılmamış.");
        note("empty_body_comment", "Metodun içi boş ve neden boş olduğu yazılmamış.");
        note("diamond", "Listenin tipi hem solda hem sağda yazılmış. Sağda tekrar gerekmez.");
        note("method_reference", "Tek işi başka bir metodu çağırmak olan kısa fonksiyon var. Metodun adı doğrudan verilebilir.");
        note("numeric_underscores", "Büyük sayı bitişik yazılmış. Binlik ayracı okumayı kolaylaştırır.");
        note("consecutive_append", "Aynı toplayıcıya peş peşe ekleme yapılmış. Tek zincirde yazılabilir.");
        note("array_copy", "Dizi eleman eleman elle kopyalanmış. Hazır kopyalama yeter.");
        note("literal_appends", "Sabit yazılar art arda eklenmiş. Baştan birleşik yazılabilir.");
        note("wrapper_new", "Sayı kutusu yeni nesne diye kurulmuş. Hazır değer kullanılmalı.");
        note("log_format", "Kayıt metni artı ile birleştirilmiş. Yer tutucu ile yazılmalı.");
        note("logger_count", "Aynı sınıfta birden fazla kayıt aracı var. Bir tane olmalı.");
        note("logger_declaration", "Kayıt aracı sınıfın ortak ve değişmez alanı olarak durmuyor.");
        note("case_change", "Metin büyütülüp hemen küçültülmüş. Gereksiz tur.");
        note("conversion_temporary", "Çeviri için ara bir nesne kurulmuş. Doğrudan çevrilebilir.");
        note("volatile_field", "Paylaşılan sayaç yalnızca görünür diye işaretlenmiş. Artış yine de karışabilir.");
        note("raw_thread", "İş için çıplak bir iş parçacığı açılmış. Ortak bir havuz kullanılmalı.");
        note("buffer_size", "Metin toplayıcı küçük başlayıp sürekli büyümüş. Baştan yeterli yer ayrılmalı.");
        note("append_char", "Tek karakter, yazı gibi eklenmiş. Karakter olarak eklenmeli.");
        note("useless_valueof", "Zaten yazı olan bir değer yeniden yazıya çevrilmiş.");
        note("unused_parameter", "Metoda gelen bir bilgi hiç kullanılmamış.");
        note("one_declaration", "Aynı satırda iki değişken tanımlanmış. Her biri kendi satırında olmalı.");
        note("unused_label", "Döngüye bir etiket konmuş ama ona gerek yok.");
        note("unused_import", "Dosyaya alınmış bir tür hiç kullanılmamış.");
        note("unnecessary_cast", "Değer zaten o tipteyken tekrar o tipe çevrilmiş.");
        note("useless_parentheses", "Bir şeyi değiştirmeyen fazla parantez var.");
        note("boolean_noise", "Zaten evet ya da hayır olan bir değer bir kez daha karşılaştırılmış.");
    }

    private ScoreSheet() {
    }

    static String label(String concept) {
        if (concept == null || concept.isBlank()) {
            return "";
        }
        return LABELS.getOrDefault(concept, concept.replace('_', ' '));
    }

    static String qualityDetail(String concept) {
        String note = QUALITY_NOTES.get(concept);
        if (note != null) {
            return note;
        }
        String name = label(concept);
        return name.isBlank() ? "" : name + " kaliteyi düşürür.";
    }

    static String kind(String group) {
        if (group == null) {
            return "";
        }
        return switch (group) {
            case "critical", "emptyCatch" -> "Kritik";
            case "high" -> "Yüksek";
            case "medium" -> "Orta";
            case "style" -> "Stil";
            case "low" -> "Düşük";
            default -> group.isBlank() ? "" : "Diğer";
        };
    }

    static String complexityDetail(String concept, String measure, String plainPoints) {
        return switch (concept) {
            case "branching" -> "Karar sayısı " + measure + ". Her if, döngü, catch ve && / || bir kez sayılır; karmaşıklığa " + plainPoints + " puan ekler.";
            case "cognitive" -> "Anlaması zor yapı " + measure + ". İç içe koşul ve döngüler karmaşıklığa " + plainPoints + " puan ekler.";
            case "npath" -> "Olası yürütme yolu " + measure + ". Dal kombinasyonu karmaşıklığa " + plainPoints + " puan ekler.";
            case "length" -> "Kod uzunluğu " + measure + ". Karmaşıklığa " + plainPoints + " puan ekler.";
            case "nesting" -> "Koşullar en fazla " + measure + " iç içe; karmaşıklığa " + plainPoints + " puan ekler.";
            case "parameters" -> "Metodun " + measure + " parametresi var. Karmaşıklığa " + plainPoints + " puan ekler.";
            case "collapsible_if" -> measure + " yerde iç içe if var. Karmaşıklığa " + plainPoints + " puan ekler.";
            case "demeter" -> measure + " yerde bir nesnenin içindeki başka nesneye zincirleme gidiliyor ve karmaşıklığa " + plainPoints + " puan ekler.";
            case "boolean_noise" -> measure + " yerde boolean gereksiz uzatılmış. Karmaşıklığa " + plainPoints + " puan ekler.";
            default -> label(concept) + " " + measure + " kez. Karmaşıklığa " + plainPoints + " puan ekler.";
        };
    }

    static String measure(String concept, int count, Integer max) {
        String text = Integer.toString(count);
        if (max != null && count == max) {
            text = "+" + grouped(max);
        }
        if ("length".equals(concept)) {
            return text + " satır";
        }
        if ("nesting".equals(concept)) {
            return text + " kat";
        }
        return text;
    }

    static String comma(double value) {
        double rounded = Math.round(value * 1000.0) / 1000.0;
        String text = Double.toString(rounded);
        if (text.contains("E") || text.contains("e")) {
            text = String.format(java.util.Locale.US, "%.3f", rounded);
        }
        if (text.endsWith(".0")) {
            text = text.substring(0, text.length() - 2);
        }
        return text.replace('.', ',');
    }

    static String minus(double value) {
        return "−" + comma(value);
    }

    static String plus(double value) {
        return "+" + comma(value);
    }

    private static String grouped(int value) {
        return String.format(java.util.Locale.GERMANY, "%,d", value);
    }

    private static void label(String concept, String text) {
        LABELS.put(concept, text);
    }

    private static void note(String concept, String text) {
        QUALITY_NOTES.put(concept, text);
    }
}
