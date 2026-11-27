package com.insurai.platform.core.product.application;

import com.insurai.platform.core.product.domain.InvalidProductException;
import com.insurai.platform.core.product.domain.ProductVersion;
import com.insurai.platform.core.product.domain.rating.Premium;
import com.insurai.platform.core.product.domain.rating.RatingEngine;
import com.insurai.platform.core.product.domain.rating.RatingInput;
import com.insurai.platform.core.product.domain.RatingException;
import com.insurai.platform.core.shared.error.ApiException;
import com.insurai.platform.core.shared.security.Caller;
import com.insurai.platform.core.shared.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Products are published, never edited. Publishing a change makes version n+1; quotes use the latest version
 * and policies keep theirs, so a rate change never silently reprices an existing policy mid-term.
 */
@Service
public class ProductCatalog {

    private final ProductRepository products;
    private final RatingEngine rating;
    private final Clock clock;

    public ProductCatalog(ProductRepository products, RatingEngine rating, Clock clock) {
        this.products = products;
        this.rating = rating;
        this.clock = clock;
    }

    public ProductVersion publish(Caller caller, ProductVersion draft) {
        caller.require(Role.MANAGER);
        int next = products.latest(draft.productId()).map(p -> p.version() + 1).orElse(1);
        ProductVersion v = draft.withVersion(next, clock.instant());
        if (!products.insert(v, caller.subject())) {
            throw ApiException.conflict("concurrent-publish", "another version of " + draft.productId()
                    + " was published at the same moment; retry");
        }
        return v;
    }

    /** Used by seeding: publish version 1 only if the product doesn't exist at all. */
    public boolean seed(ProductVersion v1) {
        if (products.latest(v1.productId()).isPresent()) {
            return false;
        }
        return products.insert(v1.withVersion(1, clock.instant()), "seed");
    }

    public ProductVersion latest(String productId) {
        return products.latest(productId).orElseThrow(() -> ApiException.notFound("product " + productId));
    }

    public ProductVersion version(String productId, int version) {
        return products.find(productId, version)
                .orElseThrow(() -> ApiException.notFound("product " + productId + " v" + version));
    }

    public List<ProductVersion> list() {
        return products.latestOfEach();
    }

    /** Rating failures are the caller's input problem, so they surface as 422 with the reason. */
    public Premium price(ProductVersion product, RatingInput input) {
        try {
            return rating.price(product, input);
        } catch (RatingException | InvalidProductException e) {
            throw ApiException.unprocessable("cannot-rate", e.getMessage());
        }
    }
}
