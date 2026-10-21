package com.bugtrace.backend;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
class WorkerPresenceAuthorizationTest {
 @Test void rejectsAnonymousHumanAndForeignIdentityBeforeDatabaseAccess(){
  var service=new WorkerPresenceService(null,new DataSourceTransactionManager());
  UUID credential=UUID.randomUUID(),project=UUID.randomUUID(),snapshot=UUID.randomUUID();
  var valid=new WorkerPresenceService.Contact("worker",snapshot,"a".repeat(64),"BASE_VERIFIED",null);
  try{
   TeamAccessContext.clear();assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(valid));assertThrows(org.springframework.web.server.ResponseStatusException.class,service::list);
   TeamAccessContext.set(new TeamAccessContext.Actor(credential,project,"Owner","HUMAN","OWNER",null,null),UUID.randomUUID());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(valid));
   TeamAccessContext.set(new TeamAccessContext.Actor(credential,project,"Worker","WORKER",null,snapshot,"worker"),UUID.randomUUID());
   assertThrows(org.springframework.web.server.ResponseStatusException.class,service::list);
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(new WorkerPresenceService.Contact("other",snapshot,"a".repeat(64),"BASE_VERIFIED",null)));
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(new WorkerPresenceService.Contact("worker",UUID.randomUUID(),"a".repeat(64),"BASE_VERIFIED",null)));
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(new WorkerPresenceService.Contact("worker",snapshot,"bad","BASE_VERIFIED",null)));
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(new WorkerPresenceService.Contact("worker",snapshot,"a".repeat(64),"INVENTED",null)));
  }finally{TeamAccessContext.clear();}
 }
}
