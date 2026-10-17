package com.bugtrace.backend;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class DiagnosisIntegrity {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static String hash(Object value) {
        Object plain=value instanceof JsonNode node?JSON.readValue(node.toString(),Object.class):JSON.convertValue(value,Object.class);
        return ContextDocuments.hash(JSON.writeValueAsString(normalize(plain)));
    }
    private static Object normalize(Object value) {
        if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),normalize(v)));return sorted;}
        if(value instanceof List<?> list)return list.stream().map(DiagnosisIntegrity::normalize).toList();
        if(value instanceof Number number)return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
}
