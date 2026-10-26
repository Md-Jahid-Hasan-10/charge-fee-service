package com.banking.chargefee.policy;

import com.banking.chargefee.dto.FeeRequest;

public interface FeePolicy {

    /** True when this rule applies to the given transfer. */
    boolean supports(FeeRequest request);

    /** Calculates the fee. Only called when supports(request) is true. */
    FeeResult calculate(FeeRequest request);
}