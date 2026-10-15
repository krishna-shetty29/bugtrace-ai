package com.bugtrace.backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bugtrace.backend.QueueContracts.*;

class QueueContractsTest {
    private final UUID attempt=UUID.randomUUID(),snapshot=UUID.randomUUID();
    private Map<String,Object> metadata(String state,String classification) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("executionId",attempt.toString());result.put("snapshotId",snapshot.toString());
        result.put("snapshotContentHash","a".repeat(64));result.put("operation","RUN_SELECTED_TEST");
        result.put("selectedTest","CheckoutIdempotencyTest");result.put("state",state);result.put("classification",classification);
        return result;
    }
    private void validate(Map<String,Object> result) { validateResult(result,attempt,snapshot,"RUN_SELECTED_TEST","CheckoutIdempotencyTest","a".repeat(64)); }
    @Test void acceptsTerminalAssertionAndInfrastructureResults() {
        assertDoesNotThrow(()->validate(metadata("FAILED","ASSERTION_FAILED")));
        assertDoesNotThrow(()->validate(metadata("ERROR","INFRASTRUCTURE_ERROR")));
        assertDoesNotThrow(()->validate(metadata("TIMED_OUT","TIMED_OUT")));
    }
    @Test void rejectsWrongAttemptAndSnapshot() {
        var result=metadata("FAILED","ASSERTION_FAILED");result.put("executionId",UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class,()->validate(result));
        var other=metadata("FAILED","ASSERTION_FAILED");other.put("snapshotId",UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class,()->validate(other));
    }
    @Test void rejectsWrongHashSelectorAndFalsePassedState() {
        var result=metadata("SUCCEEDED","ASSERTION_FAILED");assertThrows(IllegalArgumentException.class,()->validate(result));
        result.put("state","FAILED");result.put("selectedTest","OtherTest");assertThrows(IllegalArgumentException.class,()->validate(result));
        result.put("selectedTest","CheckoutIdempotencyTest");result.put("snapshotContentHash","b".repeat(64));assertThrows(IllegalArgumentException.class,()->validate(result));
    }
    @Test void rejectsCommandInjectionAndIrrelevantSelector() {
        assertThrows(IllegalArgumentException.class,()->QueueContracts.validate(new CreateJob(Operation.RUN_SELECTED_TEST,"Test & whoami",120)));
        assertThrows(IllegalArgumentException.class,()->QueueContracts.validate(new CreateJob(Operation.COMPILE,"Test",120)));
        assertDoesNotThrow(()->QueueContracts.validate(new CreateJob(Operation.RUN_TEST_SUITE,null,120)));
    }
}
