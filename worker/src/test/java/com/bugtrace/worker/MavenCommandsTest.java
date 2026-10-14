package com.bugtrace.worker;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.bugtrace.worker.WorkerController.Operation;

class MavenCommandsTest {
    @Test void usesFixedGoals() {
        var pom = Path.of("pom.xml");
        assertEquals("compile", MavenCommands.arguments(Operation.COMPILE, null, pom).getLast());
        assertEquals("test", MavenCommands.arguments(Operation.RUN_TEST_SUITE, null, pom).getLast());
        var selected = MavenCommands.arguments(Operation.RUN_SELECTED_TEST, "CheckoutIdempotencyTest", pom);
        assertTrue(selected.contains("-Dtest=CheckoutIdempotencyTest"));
        assertTrue(selected.contains("-Dsurefire.failIfNoSpecifiedTests=true"));
    }

    @Test void rejectsInjectionAndUnsupportedSelectors() {
        for (String selector : new String[] {"Test & whoami", "Test;whoami", "-DskipTests", "Test*", "Test,Other", "Test#method", "Test%PATH%", "../Test"})
            assertThrows(IllegalArgumentException.class, () -> MavenCommands.arguments(Operation.RUN_SELECTED_TEST, selector, Path.of("pom.xml")));
    }

    @Test void rejectsCancellationAsCommandAndIrrelevantSelector() {
        assertThrows(IllegalArgumentException.class, () -> MavenCommands.arguments(Operation.CANCEL_EXECUTION, null, Path.of("pom.xml")));
        assertThrows(IllegalArgumentException.class, () -> MavenCommands.arguments(Operation.COMPILE, "Test", Path.of("pom.xml")));
        assertThrows(IllegalArgumentException.class, () -> MavenCommands.arguments(Operation.RUN_SELECTED_TEST, null, Path.of("pom.xml")));
    }
}
