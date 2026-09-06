package com.marketplace.shared;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level contract for the ListingSummary cache namespaces (CodeRabbit
 * #241): the four caches that hold JDK-serialized {@code Page<ListingSummary>}
 * values (catalog-active / catalog-by-category / catalog-search in
 * CatalogService, search-results in SearchService) MUST carry the
 * serialization-schema version suffix and stay in sync across the
 * {@code @Cacheable} annotations, the invalidation set
 * ({@code CATALOG_CACHE_NAMES}) and the yml {@code spring.cache.cache-names}
 * list.
 *
 * <p>Why this is a guarded contract: ListingSummary is JDK-serialized into
 * Redis; a record-component change (B4 added {@code currency}) makes
 * pre-change entries deserialize with default-value components. The version
 * suffix is the deploy-time eviction — the new namespace never reads the old
 * entries and the 1h TTL collects them. A future component change that
 * forgets the bump would silently reintroduce the hazard; this test fails
 * until every name moves to the next suffix together.</p>
 *
 * <p>File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root resolves
 * to {@code ../}; running from the repo root is handled by the fallback (the
 * house {@code PaymentsPspFilesTest} pattern).</p>
 */
class ListingSummaryCacheContractFilesTest {

    private static final Pattern CACHEABLE_NAME = Pattern.compile(
            "cacheNames\\s*=\\s*\"([a-z0-9\\-]+)\"");

    private Path repoRoot() {
        Path fromModule = Paths.get("../");
        if (Files.isDirectory(fromModule.resolve("marketplace-catalog"))) {
            return fromModule;
        }
        return Paths.get(".");
    }

    private String read(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative));
    }

    @Test
    void catalogServiceAnnotationNamesCarryTheSchemaVersion() throws IOException {
        String source = read("marketplace-catalog/src/main/java/com/marketplace/catalog/CatalogService.java");
        assertThat(cacheableNames(source))
                .as("CatalogService's three ListingSummary caches are versioned namespaces")
                .containsExactlyInAnyOrder("catalog-active-v2", "catalog-by-category-v2", "catalog-search-v2");

        assertThat(source)
                .as("the invalidation set must clear exactly the four versioned namespaces")
                .contains("Set.of(\"catalog-active-v2\", \"catalog-by-category-v2\", \"catalog-search-v2\", \"search-results-v2\")");
    }

    @Test
    void searchServiceAnnotationNameCarriesTheSchemaVersion() throws IOException {
        String source = read("marketplace-search/src/main/java/com/marketplace/search/SearchService.java");
        assertThat(cacheableNames(source))
                .as("SearchService's ListingSummary cache is part of the same versioned namespace")
                .containsExactly("search-results-v2");
    }

    @Test
    void applicationYmlCacheNamesListMatchesTheVersionedNamespaces() throws IOException {
        String yml = read("marketplace-app/src/main/resources/application.yml");
        String namesLine = yml.lines()
                .filter(line -> line.trim().startsWith("cache-names:"))
                .findFirst().orElseThrow();
        assertThat(namesLine)
                .as("the yml cache-names list must declare the versioned namespaces "
                        + "in sync with the annotations")
                .contains("catalog-active-v2", "catalog-by-category-v2", "catalog-search-v2", "search-results-v2")
                .doesNotContain("catalog-active,", "search-results,", "catalog-search,");
    }

    private java.util.Set<String> cacheableNames(String source) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = CACHEABLE_NAME.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
