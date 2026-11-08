package com.scanner.exception.server;

import com.scanner.exception.ScannerApp;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Tarama isteği. Boş bırakılan alanlar için sunucudaki ayar dosyasındaki (SCANNER_CONFIG) değerler
 * kullanılır. Alanlar komut satırı parametrelerinin karşılığıdır.
 */
@Schema(description = "Tarama isteği. Boş bırakılan alanlarda sunucudaki scanner.properties değerleri geçerlidir.")
public record ScanRequest(
        @Schema(description = "Kaynak. Boşsa ayar dosyasındaki 'source' kullanılır.",
                allowableValues = {"server", "cloud", "git", "local"}, example = "server")
        String source,

        @Schema(description = "Taranacak Bitbucket proje anahtarları (bitbucket.projects). Boşsa ayar dosyasındaki.",
                example = "[\"PRJ\"]")
        List<String> projects,

        @Schema(description = "Hariç tutulacak repolar (bitbucket.exclude.repos): repo adı, PROJE/repo veya * ile kalıp. "
                + "Verilirse ayar dosyasındakinin yerine geçer.", example = "[\"z_atil_*\", \"nova-*\"]")
        List<String> excludeRepos,

        @Schema(description = "Sadece bu repolar taranır (bitbucket.include.repos): PROJE/repo sadece o projeyi daraltır, "
                + "listede adı geçmeyen projelerin tüm repoları alınır; proje adı olmayan kalıp tüm projelere uygulanır. "
                + "excludeRepos yine uygulanır. Verilirse ayar dosyasındakinin yerine geçer.",
                example = "[\"A/repo1\", \"A/repo2\"]")
        List<String> includeRepos,

        @Schema(description = "source=git için taranacak git adresleri (--git)")
        List<String> gitUrls,

        @Schema(description = "source=local için sunucudaki klasör yolu (--local)")
        String localDir,

        @Schema(description = "Taranacak dal (--branch). Boşsa her reponun varsayılan dalı.", example = "develop")
        String branch,

        @Schema(description = "Aranacak exception sınıflarının tam adları (--class)",
                example = "[\"com.firma.framework.CustomException\"]")
        List<String> classes,

        @Schema(description = "Argüman desenleri (--pattern), ör. 0,STRING,* veya TIP_KODU=0,STRING. "
                + "Verilirse ayar dosyasındaki desenler yok sayılır.", example = "[\"0,STRING,*\"]")
        List<String> patterns,

        @Schema(description = "Sadece doğrudan throw new ... şeklindekiler (--throw-only)")
        Boolean throwOnly,

        @Schema(description = "Import/paket kontrolü yapmadan sadece sınıf adına göre eşleştir (--lenient)")
        Boolean lenient,

        @Schema(description = "Ekran/popup/region, Jasper rapor ve process envanterini de çıkar (--ebml)")
        Boolean ebml,

        @Schema(description = "Sonuçları PostgreSQL envanteriyle eşleştirip tabloya yaz (--db)")
        Boolean db,

        @Schema(description = "Eşleştir ama tabloya yazma (--db-dry-run)")
        Boolean dbDryRun,

        @Schema(description = "Tarama kaydına açıklama (--db-note)", example = "Ekim takibi")
        String dbNote,

        @Schema(description = "Aynı anda taranacak repo sayısı (--threads)", example = "4")
        Integer threads,

        @Schema(description = "Ayar dosyasındaki diğer anahtarların üzerine yazmak için, ör. {\"git.timeout.minutes\": \"30\"}. "
                + "work.dir ve output.dir sunucu tarafından yönetilir.")
        Map<String, String> properties) {

    /** Sunucunun yönettiği, istekle değiştirilemeyen ayarlar */
    private static final List<String> RESERVED = List.of("work.dir", "output.dir", "console.limit");

    /** İsteği komut satırı parametrelerine çevirip CLI ile aynı yoldan ayar değerlerine dönüştürür. */
    Properties toOverrides() {
        List<String> args = new ArrayList<String>();
        if (localDir != null && !localDir.isBlank()) add(args, "--local", localDir);
        if (gitUrls != null) for (String u : gitUrls) add(args, "--git", u);
        add(args, "--branch", branch);
        if (classes != null) for (String c : classes) add(args, "--class", c);
        if (patterns != null) for (String p : patterns) add(args, "--pattern", p);
        if (Boolean.TRUE.equals(throwOnly)) args.add("--throw-only");
        if (Boolean.TRUE.equals(lenient)) args.add("--lenient");
        if (Boolean.TRUE.equals(ebml)) args.add("--ebml");
        if (Boolean.TRUE.equals(db)) args.add("--db");
        if (Boolean.TRUE.equals(dbDryRun)) args.add("--db-dry-run");
        add(args, "--db-note", dbNote);
        if (threads != null) add(args, "--threads", String.valueOf(threads));

        Properties p = ScannerApp.parseArgs(args.toArray(new String[0])).overrides;
        if (source != null && !source.isBlank()) p.setProperty("source", source.trim());
        if (projects != null) p.setProperty("bitbucket.projects", String.join(",", projects));
        if (includeRepos != null) p.setProperty("bitbucket.include.repos", String.join(",", includeRepos));
        if (excludeRepos != null) p.setProperty("bitbucket.exclude.repos", String.join(",", excludeRepos));
        if (properties != null) {
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (RESERVED.contains(e.getKey())) {
                    throw new IllegalArgumentException(e.getKey() + " sunucu tarafından yönetilir, istekle değiştirilemez");
                }
                p.setProperty(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return p;
    }

    private static void add(List<String> args, String option, String value) {
        if (value == null || value.isBlank()) return;
        if (value.startsWith("--")) throw new IllegalArgumentException(option + " değeri -- ile başlayamaz: " + value);
        args.add(option);
        args.add(value.trim());
    }
}
