package com.banking.chargefee.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CalculateFeeCommand(
        String transferId,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        Boolean sameOwner
) {}