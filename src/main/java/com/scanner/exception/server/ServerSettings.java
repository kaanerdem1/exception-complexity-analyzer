package com.scanner.exception.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sunucu modu ayarları (application.properties veya ortam değişkenleri):
 * SCANNER_CONFIG, SCANNER_WORK_DIR, SCANNER_OUTPUT_DIR, SCANNER_API_KEY, SCANNER_SCHEDULE_CRON,
 * SCANNER_SCHEDULE_ZONE.
 */
@ConfigurationProperties(prefix = "scanner")
public class ServerSettings {

    /** Temel ayar dosyası; her tarama isteği bunun üzerine yazar */
    private String config = "scanner.properties";
    /** Klonlanan repoların tutulduğu klasör (taramalar arasında önbellek olarak kalır) */
    private String workDir = "./scannedRepos";
    /** Her taramanın raporları bu klasörün altında tarama numarasıyla bir klasöre yazılır */
    private String outputDir = "./scanReports";
    /** Doluysa /api altındaki isteklerde X-API-Key başlığı bu değer olmalı */
    private String apiKey = "";
    /** Otomatik tarama zamanı (Spring cron: saniye dakika saat gün ay haftanın-günü); "-" kapatır */
    private String scheduleCron = "0 30 12 * * *";
    /** Cron'un yorumlandığı saat dilimi */
    private String scheduleZone = "Europe/Istanbul";

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getScheduleCron() { return scheduleCron; }
    public void setScheduleCron(String scheduleCron) { this.scheduleCron = scheduleCron; }
    public String getScheduleZone() { return scheduleZone; }
    public void setScheduleZone(String scheduleZone) { this.scheduleZone = scheduleZone; }
}
