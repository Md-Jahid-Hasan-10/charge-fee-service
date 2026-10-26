package com.banking.chargefee.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "app.fee.other-account")
public record OtherAccountFeeProperties(
        @NotNull @DecimalMin("0.00") BigDecimal flatFee,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal percentage,
        @NotNull @DecimalMin("0.00") BigDecimal maxFee
) {}