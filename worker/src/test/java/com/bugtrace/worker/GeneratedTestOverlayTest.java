package com.bugtrace.worker;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedTestOverlayTest {
    @TempDir Path temp;
    GeneratedTestOverlay.Payload payload(){String source="package com.bugtrace.fixtures.checkout_timeout; class GeneratedCheckoutReproductionTest {}\n";return new GeneratedTestOverlay.Payload(GeneratedTestOverlay.PATH,GeneratedTestOverlay.CLASS,source,GeneratedTestOverlay.sha(source),"expected signature");}
    @Test void writesOnlyExperimentAndPreservesBase() throws Exception {
        Path base=temp.resolve("base"),experiment=temp.resolve("experiment");Files.createDirectory(base);Files.writeString(base.resolve("pom.xml"),"<project/>");String hash=SnapshotFiles.hash(base);SnapshotFiles.copy(base,experiment);
        assertEquals(payload().sourceSha256(),GeneratedTestOverlay.install(experiment,payload()));
        assertEquals(hash,SnapshotFiles.hash(base));assertFalse(Files.exists(base.resolve(GeneratedTestOverlay.PATH)));assertTrue(Files.exists(experiment.resolve(GeneratedTestOverlay.PATH)));
    }
    @Test void refusesOverwrite() throws Exception {
        Path experiment=temp.resolve("experiment");Files.createDirectory(experiment);GeneratedTestOverlay.install(experiment,payload());
        assertThrows(FileAlreadyExistsException.class,()->GeneratedTestOverlay.install(experiment,payload()));
    }
    @Test void rejectsTraversalAndTamperedSource(){
        var p=payload();assertThrows(IllegalArgumentException.class,()->GeneratedTestOverlay.validate(new GeneratedTestOverlay.Payload("../Test.java",p.className(),p.source(),p.sourceSha256(),p.expectedFailureSignature())));
        assertThrows(IllegalArgumentException.class,()->GeneratedTestOverlay.validate(new GeneratedTestOverlay.Payload(p.path(),p.className(),p.source()+"changed",p.sourceSha256(),p.expectedFailureSignature())));
    }
}
