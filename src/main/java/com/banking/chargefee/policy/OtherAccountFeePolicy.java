package com.banking.chargefee.policy;

import com.banking.chargefee.config.OtherAccountFeeProperties;
import com.banking.chargefee.dto.FeeRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class OtherAccountFeePolicy implements FeePolicy {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final OtherAccountFeeProperties properties;

    public OtherAccountFeePolicy(OtherAccountFeeProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(FeeRequest request) {
        return Boolean.FALSE.equals(request.sameOwner());
    }

    @Override
    public FeeResult calculate(FeeRequest request) {
        BigDecimal percentPart = request.amount()
                .multiply(properties.percentage())
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal fee = properties.flatFee()
                .add(percentPart)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal cap = properties.maxFee();
        if (cap.signum() > 0 && fee.compareTo(cap) > 0) {
            fee = cap.setScale(2, RoundingMode.HALF_UP);
        }

        return new FeeResult(
                fee,
                "OTHER_ACCOUNT",
                "Fee for transfers to another customer's account"
        );
    }
}