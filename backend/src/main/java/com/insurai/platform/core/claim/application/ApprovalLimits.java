package com.insurai.platform.core.claim.application;

import com.insurai.platform.core.claim.domain.ApprovalChain;
import com.insurai.platform.core.product.domain.LineOfBusiness;

/** Port to the authority matrix (approval_limit). */
public interface ApprovalLimits {

    ApprovalChain.Limits forLine(LineOfBusiness line);
}
