package com.bugtrace.backend;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PatchReviewEvidenceTest {
    @TempDir Path temp;
    @Test void countsActualCases()throws Exception {
        Path xml=Files.writeString(temp.resolve("TEST.xml"),"<testsuite tests='3' failures='1' errors='0' skipped='1'><testcase name='ok'/><testcase name='fail'><failure message='actual failure'/></testcase><testcase name='skip'><skipped/></testcase></testsuite>");
        var count=PatchReviewEvidence.junit(xml);assertEquals(3,count.tests());assertEquals(1,count.failures());assertEquals(1,count.skipped());assertEquals(1,count.passed());
    }
    @Test void rejectsForgedTotals()throws Exception {Path xml=Files.writeString(temp.resolve("TEST.xml"),"<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><failure/></testcase></testsuite>");assertThrows(IllegalArgumentException.class,()->PatchReviewEvidence.junit(xml));}
    @Test void rejectsExternalEntities()throws Exception {Path xml=Files.writeString(temp.resolve("TEST.xml"),"<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///never-read'>]><testsuite tests='0' failures='0' errors='0' skipped='0'/>");assertThrows(Exception.class,()->PatchReviewEvidence.junit(xml));}
    @Test void readsWindowsLogs()throws Exception {byte[] content="BUGTRACE_SCENARIO_REPORT {}\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);byte[] bytes=new byte[content.length+2];bytes[0]=(byte)255;bytes[1]=(byte)254;System.arraycopy(content,0,bytes,2,content.length);Path path=Files.write(temp.resolve("stdout.log"),bytes);assertEquals("BUGTRACE_SCENARIO_REPORT {}\r\n",PatchReviewEvidence.decodeLog(path));}
}
