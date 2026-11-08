package com.scanner.exception.model;

/**
 * Taramada bulunan bir ekran (.ebml), region (.ebml), Jasper rapor (.dsxml) dosyası
 * veya process tanımı (process/250001-XXX.par/processdefinition.xml; klasörde .par eki olmayabilir).
 */
public final class EbmlFile {

    public enum Kind {
        SCREEN("Ekran"),
        REGION("Region"),
        POPUP("Popup"),
        REPORT("Jasper rapor"),
        PROCESS("Process"),
        /** Uzantı doğru ama beklenen paketlerin hiçbirinde değil; sadece raporda gösterilir */
        UNCLASSIFIED("Sınıflandırılmadı");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    // ---- projectMatch değerleri
    /** Proje env.project tablosunda tek kayıtla bulundu */
    public static final String PROJECT_MATCHED = "MATCHED";
    /** Aynı adda birden fazla proje var, en küçük id seçildi */
    public static final String PROJECT_AMBIGUOUS = "PROJECT_AMBIGUOUS";
    /** Proje bulunamadı, project_id boş */
    public static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";

    public Kind kind;
    /** Region için hangi kuralla bulunduğu: PREFIX (RG_ ile başlıyor), PACKAGE (ebml.region altında) veya ikisi */
    public String rule = "";
    public String projectKey = "";
    public String repo = "";
    public String module = "";
    public String fileName = "";
    public String packageName = "";
    public String file = "";
    public String link;

    /** Process için klasör adındaki numara: 250001-RISM.par veya 250001-RISM -> 250001 */
    public Long processId;
    /** Process için processdefinition.xml içindeki label değeri */
    public String processName = "";
    /** Process için processdefinition.xml içindeki name değeri (env.process.no ile eşleştirilir) */
    public String processCode = "";
    /** Process için klasör adında '-' işaretinden sonraki kısım (.par eki hariç): 250001-RISM.par -> RISM (env.process.name) */
    public String processShortName = "";

    /** env.project tablosunda aranan proje adı */
    public String projectName = "";
    /** Sırayla denenecek adlar; ilki projectName */
    public final java.util.List<String> projectNameCandidates = new java.util.ArrayList<String>();
    // ---- Veritabanı eşleştirme sonucu
    public Long projectId;
    /** MATCHED, PROJECT_NOT_FOUND veya PROJECT_AMBIGUOUS */
    public String projectMatch = "";
}
