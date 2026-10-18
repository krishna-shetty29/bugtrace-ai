package com.bugtrace.backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class PatchReviewServiceTest {
    private static class StubJdbc extends JdbcTemplate {
        List<Map<String,Object>> rows=List.of();
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args){return rows;}
    }
    private PatchReviewService service(JdbcTemplate jdbc){
        var manager=new PlatformTransactionManager(){public TransactionStatus getTransaction(TransactionDefinition definition){return new SimpleTransactionStatus();}public void commit(TransactionStatus status){}public void rollback(TransactionStatus status){}};
        return new PatchReviewService(jdbc,manager,null,null,"C:/reviews/workspace","C:/reviews/evidence");
    }
    @Test void rejectsIncompleteRegistrationBeforeFilesystemAccess(){assertThrows(ResponseStatusException.class,()->service(new StubJdbc()).register(UUID.randomUUID(),new PatchReviewService.Register(null,null,null,null)));}
    @Test void rejectsUnknownDecision(){assertThrows(ResponseStatusException.class,()->service(new StubJdbc()).decide(UUID.randomUUID(),UUID.randomUUID(),new PatchReviewService.Decision("APPLY","hash","reason",true,true,true,true,true)));}
    @Test void rejectsMissingReason(){assertThrows(ResponseStatusException.class,()->service(new StubJdbc()).decide(UUID.randomUUID(),UUID.randomUUID(),new PatchReviewService.Decision("APPROVE","hash","",true,true,true,true,true)));}
    @Test void enforcesInvestigationOwnership(){assertThrows(ResponseStatusException.class,()->service(new StubJdbc()).get(UUID.randomUUID(),UUID.randomUUID()));}
    @Test void rejectsTamperedSavedPayload(){var jdbc=new StubJdbc();jdbc.rows=List.of(Map.of("payload","{}","payload_hash","wrong"));assertThrows(ResponseStatusException.class,()->service(jdbc).get(UUID.randomUUID(),UUID.randomUUID()));}
    @Test void blocksApprovalWithoutInspection(){var input=new PatchReviewService.Decision("APPROVE","hash","reason",false,true,true,true,true);assertThrows(ResponseStatusException.class,()->PatchReviewService.checkApproval(input,Map.of("targetedChecksPassed",true,"suiteExceptionRecognized",true,"fullSuiteFailures",1)));}
    @Test void blocksUnacknowledgedSuiteFailure(){var input=new PatchReviewService.Decision("APPROVE","hash","reason",true,true,true,true,false);assertThrows(ResponseStatusException.class,()->PatchReviewService.checkApproval(input,Map.of("targetedChecksPassed",true,"suiteExceptionRecognized",true,"fullSuiteFailures",1)));}
    @Test void blocksUnexpectedFailuresEvenWhenAcknowledged(){var input=new PatchReviewService.Decision("APPROVE","hash","reason",true,true,true,true,true);assertThrows(ResponseStatusException.class,()->PatchReviewService.checkApproval(input,Map.of("targetedChecksPassed",true,"suiteExceptionRecognized",false,"fullSuiteFailures",2)));}
    @Test void allowsExplicitKnownExceptionWithoutGrantingApplication(){var input=new PatchReviewService.Decision("APPROVE","hash","reason",true,true,true,true,true);assertDoesNotThrow(()->PatchReviewService.checkApproval(input,Map.of("targetedChecksPassed",true,"suiteExceptionRecognized",true,"fullSuiteFailures",1)));}
}
