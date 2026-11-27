package com.insurai.platform.core.product.application;

import com.insurai.platform.core.product.domain.ProductVersion;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    /** False if that (product, version) already exists: versions are written once. */
    boolean insert(ProductVersion product, String createdBy);

    Optional<ProductVersion> find(String productId, int version);

    Optional<ProductVersion> latest(String productId);

    List<ProductVersion> latestOfEach();
}
