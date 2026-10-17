package com.bugtrace.backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ControlledScenarioSourceTest {
    final JsonMapper json=JsonMapper.builder().build();
    String output(String scenario,int repetitions){return json.writeValueAsString(new GeneratedReproduction.Spec(ControlledScenarioSource.entryPoint(scenario,repetitions),ControlledScenarioSource.ASSERTION,ControlledScenarioSource.fault(scenario),List.of("id"),ControlledScenarioSource.SIGNATURE,"test proposal"));}
    @Test void appendsExactPlatformHarnessForEveryScenario(){for(String scenario:ControlledScenarioSource.SCENARIOS){var spec=ControlledScenarioSource.parse(output(scenario,20),Set.of("id"),scenario,20);assertEquals(new ControlledScenarioSource.Parameters(scenario,20),ControlledScenarioSource.validateSource(spec.testSource()));assertTrue(spec.testSource().length()<12000);assertDoesNotThrow(()->GeneratedReproduction.validateSource(spec.testSource()));}}
    @Test void rejectsChangedCountAndExtraStatements(){assertThrows(Exception.class,()->ControlledScenarioSource.parse(output("DELAYED_RESPONSE",20).replace("20);","1);"),Set.of("id"),"DELAYED_RESPONSE",20));assertThrows(Exception.class,()->ControlledScenarioSource.parse(output("DELAYED_RESPONSE",20).replace("20);","20); System.exit(0);"),Set.of("id"),"DELAYED_RESPONSE",20));}
    @Test void rejectsModifiedHarness(){var spec=ControlledScenarioSource.parse(output("CONCURRENT_RETRIES",20),Set.of("id"),"CONCURRENT_RETRIES",20);assertThrows(Exception.class,()->ControlledScenarioSource.validateSource(spec.testSource().replace("assertEquals(0, duplicates","assertEquals(duplicates, duplicates")));}
    @Test void rejectsUnknownEvidenceAndScenario(){assertThrows(Exception.class,()->ControlledScenarioSource.parse(output("DELAYED_RESPONSE",20),Set.of("other"),"DELAYED_RESPONSE",20));assertThrows(Exception.class,()->ControlledScenarioSource.parameters("SHELL",20));assertThrows(Exception.class,()->ControlledScenarioSource.parameters("DELAYED_RESPONSE",1));}
    @Test void storedVersionKeepsPinnedContract(){var spec=ControlledScenarioSource.parse(output("RETRY_AFTER_COMPLETION",20),Set.of("id"),"RETRY_AFTER_COMPLETION",20);assertEquals(spec,ControlledScenarioSource.stored(json.writeValueAsString(spec),Set.of("id"),"RETRY_AFTER_COMPLETION",20));assertThrows(Exception.class,()->ControlledScenarioSource.stored(json.writeValueAsString(spec),Set.of("id"),"RETRY_AFTER_COMPLETION",21));}
}
