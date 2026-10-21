package com.eventcore.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventcore.catalog.application.CatalogCheck;
import com.eventcore.catalog.application.EventCatalog;
import com.eventcore.catalog.domain.VersionStatus;
import com.eventcore.platform.json.JsonCanonicalizer;
import com.eventcore.platform.json.StrictJsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;

class ShippedCatalogTest {

    private static final String LOCATION = "classpath:eventcore/catalog/";

    private final StrictJsonReader reader = new StrictJsonReader();
    private final EventCatalog catalog = new CatalogLoader(new DefaultResourceLoader(), reader).load(LOCATION);

    @ParameterizedTest(name = "{0} v{1} is {2}")
    @CsvSource(delimiter = '|', value = {
            "order.placed|1|DEPRECATED|{\"orderId\":\"o-1\",\"customerId\":\"c-1\",\"totalCents\":1500}",
            "order.placed|2|ACTIVE|{\"orderId\":\"o-1\",\"customerId\":\"c-1\",\"total\":{\"amount\":\"15.00\",\"currency\":\"BRL\"},\"items\":[{\"sku\":\"sku-1\",\"quantity\":1,\"unitPrice\":{\"amount\":\"15.00\",\"currency\":\"BRL\"}}]}",
            "payment.captured|1|ACTIVE|{\"paymentId\":\"p-1\",\"orderId\":\"o-1\",\"amount\":\"15.00\",\"currency\":\"BRL\",\"method\":\"PIX\"}",
            "customer.registered|2|ACTIVE|{\"customerId\":\"c-1\",\"givenName\":\"Ana\",\"familyName\":\"Silva\"}"
    })
    void acceptsValidExamplesOfEveryAcceptedVersion(String type, int version, VersionStatus status, String data) {
        assertThat(catalog.check(type, version, json(data))).isEqualTo(new CatalogCheck.Accepted(status));
    }

    @Test
    void doesNotAcceptRetiredVersions() {
        assertThat(catalog.check("customer.registered", 1, json("{\"customerId\":\"c-1\",\"name\":\"Ana\"}")))
                .isInstanceOfSatisfying(CatalogCheck.UnsupportedVersion.class,
                        unsupported -> assertThat(unsupported.acceptedVersions()).containsExactly(2));
    }

    @Test
    void publishedSchemasMatchTheLockFile() throws IOException {
        assertThat(schemaDigests()).isEqualTo(lockedDigests());
    }

    private Map<String, String> schemaDigests() throws IOException {
        Resource[] schemas = new PathMatchingResourcePatternResolver().getResources(LOCATION + "*/v*.schema.json");
        Map<String, String> digests = new TreeMap<>();
        JsonCanonicalizer canonicalizer = new JsonCanonicalizer();
        for (Resource schema : schemas) {
            String key = parentDirectoryName(schema) + "/" + schema.getFilename().replace(".schema.json", "");
            try (InputStream input = schema.getInputStream()) {
                digests.put(key, sha256(canonicalizer.canonicalize(reader.read(input))));
            }
        }
        return digests;
    }

    private static String parentDirectoryName(Resource resource) throws IOException {
        String path = resource.getURL().getPath();
        String withoutFile = path.substring(0, path.lastIndexOf('/'));
        return withoutFile.substring(withoutFile.lastIndexOf('/') + 1);
    }

    private static Map<String, String> lockedDigests() throws IOException {
        try (InputStream input = ShippedCatalogTest.class.getResourceAsStream("/catalog.lock")) {
            assertThat(input).as("catalog.lock must exist on the test classpath").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(" ", 2))
                    .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1], (left, right) -> left,
                            TreeMap::new));
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode json(String text) {
        return reader.read(text.getBytes(StandardCharsets.UTF_8));
    }
}
