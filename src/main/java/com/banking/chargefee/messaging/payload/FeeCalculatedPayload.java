package com.banking.chargefee.messaging.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

public record FeeCalculatedPayload(
        String transferId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fee,
        String feeType
) {}