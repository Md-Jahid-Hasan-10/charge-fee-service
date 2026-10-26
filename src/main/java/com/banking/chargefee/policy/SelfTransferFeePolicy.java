package com.banking.chargefee.policy;

import com.banking.chargefee.dto.FeeRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class SelfTransferFeePolicy implements FeePolicy {

    @Override
    public boolean supports(FeeRequest request) {
        return Boolean.TRUE.equals(request.sameOwner());
    }

    @Override
    public FeeResult calculate(FeeRequest request) {
        return new FeeResult(
                BigDecimal.ZERO.setScale(2),
                "SELF_TRANSFER",
                "No fee for transfers between your own accounts"
        );
    }
}