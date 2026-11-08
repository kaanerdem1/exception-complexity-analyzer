package com.scanner.exception.server;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.ArrayList;
import java.util.List;

/**
 * Tarayıcıyı REST API olarak çalıştırır (java -jar system-code-analyzer-1.0.0.jar --server).
 * Swagger arayüzü: /swagger-ui.html, sağlık kontrolleri: /actuator/health/liveness ve /readiness.
 */
@SpringBootApplication
@EnableConfigurationProperties(ServerSettings.class)
@EnableScheduling
@OpenAPIDefinition(
        info = @Info(title = "Exception Kullanım Tarayıcı",
                version = "1.0.0",
                description = "Bitbucket, git adresi veya yerel klasördeki Java kodunda exception "
                        + "kullanımlarını tarar; Excel raporu üretir. Taramalar sırayla, arka planda çalışır: "
                        + "POST /api/scans ile başlatın, GET /api/scans/{id} ile durumu izleyin, raporu "
                        + "GET /api/scans/{id}/report ile indirin. Ayrıca her gün sunucudaki ayarlarla "
                        + "otomatik tarama yapılır (GET /api/schedule)."),
        security = @SecurityRequirement(name = "apiKey"))
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = ApiKeyFilter.HEADER,
        description = "SCANNER_API_KEY tanımlıysa gerekli; tanımlı değilse boş bırakılabilir")
public class ScanServerApplication {

    /** IDE'den (Run 'ScanServerApplication') doğrudan sunucu olarak başlatmak için */
    public static void main(String[] args) {
        start(args);
    }

    public static void start(String[] args) {
        ExtraCaBundle.installFromEnv(); // SSL ilk kullanılmadan önce
        // "--server" Spring'e geçerse "server" önekli ayarlarla karışır
        List<String> rest = new ArrayList<String>();
        for (String a : args) if (!"--server".equals(a)) rest.add(a);
        SpringApplication.run(ScanServerApplication.class, rest.toArray(new String[0]));
    }
}
