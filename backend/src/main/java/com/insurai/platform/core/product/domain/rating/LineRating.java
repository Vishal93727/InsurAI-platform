package com.insurai.platform.core.product.domain.rating;

import com.insurai.platform.core.product.domain.LineOfBusiness;

import java.util.List;

/** Strategy per line of business: which inputs it insists on and the order of its factor chain. */
public interface LineRating {

    LineOfBusiness line();

    List<RatingStep> chain();
}
