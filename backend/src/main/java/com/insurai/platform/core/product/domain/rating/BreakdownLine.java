package com.insurai.platform.core.product.domain.rating;

/** One step of the price: what was applied and the running premium after it, in minor units. */
public record BreakdownLine(String step, String detail, long runningMinor) {
}
