package com.banking.chargefee.policy;

import java.math.BigDecimal;

public record FeeResult(BigDecimal fee, String feeType, String description) {}