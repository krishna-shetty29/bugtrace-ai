package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SelectedHistoryTest {
    @TempDir Path repository;
    private void initialize() throws Exception {
        SelectedHistory.run(repository,"init");SelectedHistory.run(repository,"config","user.name","Fixture test");SelectedHistory.run(repository,"config","user.email","fixture@example.invalid");
        SelectedHistory.run(repository,"config","commit.gpgsign","false");Files.createDirectory(repository.resolve("empty-hooks"));SelectedHistory.run(repository,"config","core.hooksPath",repository.resolve("empty-hooks").toString());
        Files.createDirectories(repository.resolve("fixture"));Files.writeString(repository.resolve("fixture/README.md"),"Payment retry baseline\n");Files.writeString(repository.resolve("outside.txt"),"OUTSIDE_MODULE_SENTINEL\n");
        SelectedHistory.run(repository,"add","fixture/README.md","outside.txt");SelectedHistory.run(repository,"commit","-m","Add payment baseline");
    }
    @Test void importsOnlySelectedModuleDiffWithExplicitHistoricalLabels() throws Exception {
        initialize();String commit=SelectedHistory.run(repository,"rev-parse","HEAD").strip();
        var docs=new SelectedHistory().read(repository,commit,"fixture",List.of(commit));
        assertEquals(2,docs.size());assertEquals("HISTORICAL_COMMIT",docs.getFirst().origin());assertEquals("DIFF_LINE",docs.getLast().lineKind());
        assertTrue(docs.getLast().body().contains("Payment retry baseline"));assertFalse(docs.getLast().body().contains("OUTSIDE_MODULE_SENTINEL"));assertTrue(docs.getLast().sourceRef().contains(commit));
    }
    @Test void rejectsLaterReferenceFixAndOptionInjection() throws Exception {
        initialize();String buggy=SelectedHistory.run(repository,"rev-parse","HEAD").strip();
        SelectedHistory.run(repository,"switch","-c","reference-fix");Files.writeString(repository.resolve("fixture/README.md"),"REFERENCE_FIX_SENTINEL\n");SelectedHistory.run(repository,"add","fixture/README.md");SelectedHistory.run(repository,"commit","-m","Reference fix");String fixed=SelectedHistory.run(repository,"rev-parse","HEAD").strip();
        assertThrows(IllegalArgumentException.class,()->new SelectedHistory().read(repository,buggy,"fixture",List.of(fixed)));
        assertThrows(IllegalArgumentException.class,()->new SelectedHistory().read(repository,buggy,"fixture",List.of("--all")));
        var safe=new SelectedHistory().read(repository,buggy,"fixture",List.of(buggy));assertFalse(safe.toString().contains("REFERENCE_FIX_SENTINEL"));
    }
    @Test void noSelectionDoesNotReadRepository() throws Exception {
        assertTrue(new SelectedHistory().read(repository.resolve("not-a-repository"),"not-a-hash","fixture",List.of()).isEmpty());
    }
}
