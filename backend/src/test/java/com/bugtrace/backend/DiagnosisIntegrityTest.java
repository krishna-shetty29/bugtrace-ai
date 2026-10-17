package com.bugtrace.backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosisIntegrityTest {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void canonicalHashSurvivesJsonbKeyOrdering(){assertEquals(DiagnosisIntegrity.hash(json.readTree("{\"a\":1,\"b\":{\"x\":2,\"y\":3}}")),DiagnosisIntegrity.hash(json.readTree("{\"b\":{\"y\":3,\"x\":2},\"a\":1}")));}
    @Test void detectsChangedContentAndArrayOrdering(){assertNotEquals(DiagnosisIntegrity.hash(List.of("a","b")),DiagnosisIntegrity.hash(List.of("b","a")));assertNotEquals(DiagnosisIntegrity.hash(Map.of("quote","old")),DiagnosisIntegrity.hash(Map.of("quote","new")));}
    @Test void preservesNumericMeaningAcrossJsonSerialization(){assertEquals(DiagnosisIntegrity.hash(json.readTree("{\"rate\":1.0}")),DiagnosisIntegrity.hash(json.readTree("{\"rate\":1}")));}
    @Test void numericAndTextValuesDoNotCollide(){assertNotEquals(DiagnosisIntegrity.hash(Map.of("line",1)),DiagnosisIntegrity.hash(Map.of("line","1")));}
}
