package com.banking.chargefee.dto;

import java.math.BigDecimal;

public record FeeResponse(
        BigDecimal fee,
        String feeType,
        String description
) {}