package com.bugtrace.worker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BackendOriginTest {
    @Test void acceptsOnlyOperatorOrigins() {
        assertEquals("https://demo.example",BackendOrigin.normalize("https://demo.example:443/"));
        assertEquals("http://127.0.0.1:8080",BackendOrigin.normalize("http://127.0.0.1:8080/"));
        for(String origin:new String[]{"http://demo.example","https://user:secret@demo.example","https://demo.example/api","https://demo.example?x=1","https://demo.example#x","https://demo.example:0","https://demo.example:65536","https://demo.example/%2e%2e"," https://demo.example","file:///tmp","https://demo.example\\evil"})
            assertThrows(IllegalArgumentException.class,()->BackendOrigin.normalize(origin));
    }
}
