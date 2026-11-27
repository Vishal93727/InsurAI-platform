package com.insurai.platform.core.product.domain;

/** The inputs can't be priced by this product version: a missing factor, a value outside every band. */
public class RatingException extends RuntimeException {

    public RatingException(String message) {
        super(message);
    }
}
