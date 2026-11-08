package com.scanner.exception.server;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

/**
 * EXTRA_CA_BUNDLE ortam değişkeninde verilen PEM dosyasındaki sertifikaları (ör. şirket CA'sı veya
 * platformun güvenilen CA paketi) JDK'nın varsayılan sertifikalarına ekler. Bitbucket REST
 * çağrıları bu truststore'u kullanır; git için aynı dosya GIT_SSL_CAINFO ile ayrıca verilmelidir.
 */
final class ExtraCaBundle {

    private ExtraCaBundle() { }

    static void installFromEnv() {
        String bundle = System.getenv("EXTRA_CA_BUNDLE");
        if (bundle == null || bundle.isBlank() || System.getProperty("javax.net.ssl.trustStore") != null) return;
        Path pem = Paths.get(bundle);
        if (!Files.isRegularFile(pem)) {
            System.err.println("Uyarı: EXTRA_CA_BUNDLE bulunamadı: " + pem);
            return;
        }
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            Path cacerts = Paths.get(System.getProperty("java.home"), "lib", "security", "cacerts");
            try (InputStream in = Files.newInputStream(cacerts)) {
                ks.load(in, "changeit".toCharArray());
            }
            Collection<? extends Certificate> certs;
            try (InputStream in = Files.newInputStream(pem)) {
                certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            }
            int added = 0;
            for (Certificate c : certs) {
                if (ks.getCertificateAlias(c) != null) continue;
                ks.setCertificateEntry("extra-ca-" + (++added), c);
            }
            Path out = Files.createTempFile("truststore", ".p12");
            try (OutputStream os = Files.newOutputStream(out)) {
                ks.store(os, "changeit".toCharArray());
            }
            System.setProperty("javax.net.ssl.trustStore", out.toString());
            System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
            System.setProperty("javax.net.ssl.trustStoreType", ks.getType());
            System.out.println("EXTRA_CA_BUNDLE: " + certs.size() + " sertifika okundu, " + added + " tanesi eklendi");
        } catch (Exception e) {
            System.err.println("Uyarı: EXTRA_CA_BUNDLE yüklenemedi (" + pem + "): " + e);
        }
    }
}
