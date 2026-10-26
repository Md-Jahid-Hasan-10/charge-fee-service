package com.banking.chargefee.policy;

import com.banking.chargefee.config.OtherAccountFeeProperties;
import com.banking.chargefee.dto.FeeRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherAccountFeePolicyTest {

    private static FeeRequest request(String amount, boolean sameOwner) {
        return new FeeRequest("ACC-1", "ACC-2", new BigDecimal(amount), sameOwner);
    }

    private static OtherAccountFeePolicy policy(String flat, String percentage, String max) {
        return new OtherAccountFeePolicy(new OtherAccountFeeProperties(
                new BigDecimal(flat), new BigDecimal(percentage), new BigDecimal(max)));
    }

    @Test
    void flatFeeOnly() {
        FeeResult result = policy("10.00", "0.00", "0.00").calculate(request("100.00", false));
        assertEquals(new BigDecimal("10.00"), result.fee());
        assertEquals("OTHER_ACCOUNT", result.feeType());
    }

    @Test
    void flatPlusPercentage() {
        FeeResult result = policy("10.00", "1.5", "0.00").calculate(request("1000.00", false));
        assertEquals(new BigDecimal("25.00"), result.fee());
    }

    @Test
    void feeIsCappedAtMax() {
        FeeResult result = policy("10.00", "1.5", "20.00").calculate(request("1000.00", false));
        assertEquals(new BigDecimal("20.00"), result.fee());
    }

    @Test
    void percentageRoundsHalfUp() {
        FeeResult result = policy("0.00", "1.5", "0.00").calculate(request("33.33", false));
        assertEquals(new BigDecimal("0.50"), result.fee());
    }

    @Test
    void policiesApplyToTheRightTransfers() {
        OtherAccountFeePolicy other = policy("10.00", "0.00", "0.00");
        SelfTransferFeePolicy self = new SelfTransferFeePolicy();

        assertTrue(self.supports(request("50.00", true)));
        assertFalse(self.supports(request("50.00", false)));
        assertTrue(other.supports(request("50.00", false)));
        assertFalse(other.supports(request("50.00", true)));
    }

    @Test
    void selfTransferIsAlwaysFree() {
        FeeResult result = new SelfTransferFeePolicy().calculate(request("999999.99", true));
        assertEquals(new BigDecimal("0.00"), result.fee());
        assertEquals("SELF_TRANSFER", result.feeType());
    }
}