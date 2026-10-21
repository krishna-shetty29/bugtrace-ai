package com.bugtrace.backend;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class TeamReviewHttpContractTest {
 @Test void completeDecisionPayloadReachesReviewerService()throws Exception {
  var service=mock(PatchReviewService.class);UUID investigation=UUID.randomUUID(),review=UUID.randomUUID();var expected=new PatchReviewService.Decision("APPROVE","b".repeat(64),"Permission boundary test",true,true,true,true,false);
  when(service.decide(investigation,review,expected)).thenReturn(Map.of("state","APPROVED"));
  var mvc=MockMvcBuilders.standaloneSetup(new PatchReviewController(service)).build();
  mvc.perform(post("/api/investigations/"+investigation+"/patch-reviews/"+review+"/decision").contentType(MediaType.APPLICATION_JSON).content(JsonMapper.builder().build().writeValueAsString(expected))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("APPROVED"));
  verify(service).decide(investigation,review,expected);
 }
 @Test void omittedAcknowledgmentBooleansRemainInvalid()throws Exception {
  var service=mock(PatchReviewService.class);var mvc=MockMvcBuilders.standaloneSetup(new PatchReviewController(service)).build();
  mvc.perform(post("/api/investigations/"+UUID.randomUUID()+"/patch-reviews/"+UUID.randomUUID()+"/decision").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
}
