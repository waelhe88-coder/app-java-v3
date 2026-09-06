package com.marketplace.search;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Search service depends on CatalogSearchPort abstraction only,
 * not on catalog internals. This decouples search from catalog module.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    private final CatalogSearchPort catalogSearchPort;

    public SearchService(CatalogSearchPort catalogSearchPort) {
        this.catalogSearchPort = catalogSearchPort;
    }

    // The -v2 suffix is the ListingSummary serialization-schema namespace —
    // see CatalogService.CATALOG_CACHE_NAMES: the record gained a currency
    // component (B4), so pre-change cache entries must never be read (CodeRabbit
    // #241). Bump together with the other three names on every ListingSummary
    // component change.
    @Cacheable(cacheNames = "search-results-v2", key = "(#query == null ? '' : #query.trim()) + '|' + (#category == null ? '' : #category.trim()) + '|' + #pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    public Page<ListingSummary> search(String query, String category, Pageable pageable) {
        return search(new SearchCriteria(query, category, null, null), pageable);
    }

    public Page<ListingSummary> search(SearchCriteria criteria, Pageable pageable) {
        String query = criteria.query();
        String category = criteria.category();
        if (query != null && !query.isBlank()) {
            // Raw user input passed through: the official websearch_to_tsquery
            // (ProviderListingRepository) parses it leniently and supports
            // "quoted phrases", OR and -exclusion. The former hand-mangling
            // (replaceAll("\\s+", " & ")) both corrupted the user's phrase
            // intent and fed to_tsquery invalid syntax for quotes/parens/dashes
            // (SQL exception -> HTTP 500).
            return catalogSearchPort.searchFullText(query.trim(), pageable);
        }
        if (criteria.minPrice() != null || criteria.maxPrice() != null) {
            return catalogSearchPort.searchByCriteria(criteria, pageable);
        }
        if (category != null && !category.isBlank()) {
            return catalogSearchPort.listByCategory(category, pageable);
        }
        return catalogSearchPort.listActive(pageable);
    }

    public Page<ListingSummary> searchByCategory(String category, Pageable pageable) {
        return catalogSearchPort.listByCategory(category, pageable);
    }

    public Page<ListingSummary> searchAll(Pageable pageable) {
        return catalogSearchPort.listActive(pageable);
    }
}
