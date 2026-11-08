package com.scanner.exception.server;

import com.scanner.exception.ScannerApp;
import com.scanner.exception.config.Config;
import com.scanner.exception.db.DeepScanCheck;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Günlük otomatik tarama (varsayılan her gün 12:30). Sunucudaki scanner.properties ile, Swagger'dan boş istek ({})
 * gönderilmiş gibi tarar. Zaman SCANNER_SCHEDULE_CRON / SCANNER_SCHEDULE_ZONE ile değişir.
 */
@Component
class ScheduledScan {

    private final ScanJobService service;
    private final ServerSettings settings;

    ScheduledScan(ScanJobService service, ServerSettings settings) {
        this.service = service;
        this.settings = settings;
        if (enabled()) {
            CronExpression.parse(settings.getScheduleCron()); // hatalı ifade açılışta fark edilsin
            ScannerApp.log("Otomatik tarama: " + settings.getScheduleCron() + " (" + settings.getScheduleZone()
                    + "), ilk çalışma " + info().nextRun);
        } else {
            ScannerApp.log("Otomatik tarama kapalı (SCANNER_SCHEDULE_CRON=-)");
        }
    }

    /**
     * Pod planlanan saatte kapalıysa (yeniden dağıtım, node bakımı) o günün taraması kaçmasın:
     * son planlanan zamandan sonra otomatik tarama yapılmamışsa açılışta hemen başlatılır.
     * Hiç otomatik tarama yapılmamışsa (ilk kurulum) beklenir, planlanan saatte çalışır.
     */
    @EventListener(ApplicationReadyEvent.class)
    void catchUp() {
        if (!enabled()) return;
        Instant lastScheduled = null;
        for (ScanJob j : service.list()) {
            if (j.trigger == ScanJob.Trigger.SCHEDULE) {
                lastScheduled = j.createdAt;
                break; // liste en yeniden eskiye
            }
        }
        if (lastScheduled == null) return;
        ZonedDateTime missed = previousFire(ZonedDateTime.now(ZoneId.of(settings.getScheduleZone())));
        if (missed != null && lastScheduled.isBefore(missed.toInstant())) {
            ScannerApp.log("Otomatik tarama kaçırılmış (" + missed + "), şimdi başlatılıyor");
            run();
        }
    }

    /** now'dan önceki son planlanan zaman (en fazla 35 gün geriye bakılır) */
    private ZonedDateTime previousFire(ZonedDateTime now) {
        CronExpression cron = CronExpression.parse(settings.getScheduleCron());
        ZonedDateTime t = cron.next(now.minusDays(35));
        ZonedDateTime last = null;
        while (t != null && t.isBefore(now)) {
            last = t;
            t = cron.next(t);
        }
        return last;
    }

    @Scheduled(cron = "${scanner.schedule-cron}", zone = "${scanner.schedule-zone}")
    void run() {
        if (service.hasActive(ScanJob.Trigger.SCHEDULE)) {
            ScannerApp.log("Otomatik tarama atlandı: önceki otomatik tarama hâlâ sırada veya çalışıyor");
            return;
        }
        try {
            if (!deepScanFresh(service.defaultConfig())) return;
            ScanJob job = service.submit(null, ScanJob.Trigger.SCHEDULE);
            ScannerApp.log("Otomatik tarama sıraya alındı: " + job.id);
        } catch (Exception e) {
            ScannerApp.log("HATA: otomatik tarama başlatılamadı: " + e.getMessage());
        }
    }

    /**
     * Deep-scanner son db.deep.scan.max.age.days gün içinde başarıyla bitmediyse envanter güncel değildir:
     * tarama eski sınıf/metot id'leriyle eşleşeceği için yapılmaz. Kontrol edilemezse de yapılmaz.
     */
    private boolean deepScanFresh(Config cfg) {
        Config.Db db = cfg.db;
        if (db.deepScanMaxAgeDays == 0) return true;
        String table = db.deepScanTable;
        if (db.url.isEmpty()) {
            ScannerApp.log("Otomatik tarama pas geçildi: deep-scanner taraması kontrol edilemedi (db.url boş). "
                    + "Kontrolü kapatmak için db.deep.scan.max.age.days=0");
            return false;
        }
        try {
            DeepScanCheck.Result r = DeepScanCheck.check(db);
            if (r.fresh) {
                ScannerApp.log("Deep-scanner taraması güncel (son başarılı: " + r.lastSuccess + ")");
                return true;
            }
            ScannerApp.log("Deep-scanner taraması güncel olmadığı için hata taraması pas geçildi: " + table
                    + " tablosunda son " + db.deepScanMaxAgeDays + " gün içinde " + db.deepScanStatusColumn + "="
                    + db.deepScanSuccessStatus + " kayıt yok (son başarılı: "
                    + (r.lastSuccess == null ? "hiç yok" : r.lastSuccess) + ")");
        } catch (Exception e) {
            ScannerApp.log("Otomatik tarama pas geçildi: deep-scanner taraması kontrol edilemedi (" + table + "): "
                    + e.getMessage());
        }
        return false;
    }

    Info info() {
        Info i = new Info();
        i.enabled = enabled();
        i.cron = settings.getScheduleCron();
        i.zone = settings.getScheduleZone();
        if (i.enabled) {
            ZoneId zone = ZoneId.of(settings.getScheduleZone());
            ZonedDateTime next = CronExpression.parse(settings.getScheduleCron()).next(ZonedDateTime.now(zone));
            i.nextRun = next == null ? null : next.toOffsetDateTime().toString();
        }
        return i;
    }

    private boolean enabled() {
        String c = settings.getScheduleCron();
        return c != null && !c.isBlank() && !Scheduled.CRON_DISABLED.equals(c.trim());
    }

    @Schema(description = "Otomatik tarama zamanı")
    static class Info {
        @Schema(description = "Açık mı")
        public boolean enabled;
        @Schema(description = "Spring cron ifadesi (saniye dakika saat gün ay haftanın-günü)", example = "0 30 12 * * *")
        public String cron;
        @Schema(example = "Europe/Istanbul")
        public String zone;
        @Schema(description = "Bir sonraki çalışma", example = "2026-10-11T02:00+03:00")
        public String nextRun;
    }
}
