package com.bugtrace.worker;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

public final class ExecutionEvidence {
    private ExecutionEvidence() {}
    public enum Outcome { PASSED, ASSERTION_FAILED, COMPILATION_FAILED, TIMED_OUT, RESOURCE_LIMIT, INFRASTRUCTURE_ERROR, CANCELLED }
    public record Failure(String className, String testName, String type, String message) {}
    public record ReportFile(String name, String sha256, long bytes) {}
    public record Reports(int tests, int failures, int errors, int skipped,
                          List<Failure> details, List<ReportFile> files) {}

    public static Reports collect(Path experiment, Path evidence) throws Exception {
        Path destination = evidence.resolve("junit");
        Files.createDirectory(destination);
        if (experiment == null) return empty();
        Path source = experiment.resolve("target/surefire-reports");
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return empty();
        SnapshotFiles.requireRealPath(source);
        List<Path> reports;
        try (var paths = Files.list(source)) {
            reports = paths.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.getFileName().toString().endsWith(".xml"))
                .sorted().limit(101).toList();
        }
        if (reports.size() > 100) throw new IllegalArgumentException("More than 100 JUnit reports");
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        List<Failure> details = new ArrayList<>();
        List<ReportFile> files = new ArrayList<>();
        for (Path report : reports) {
            SnapshotFiles.requireRealPath(report);
            String name = report.getFileName().toString();
            if (!name.matches("[A-Za-z0-9_.$-]+") || !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS) || Files.size(report) > 4L * 1024 * 1024)
                throw new IllegalArgumentException("Unsafe or oversized JUnit report");
            Path copy = destination.resolve(name);
            Files.copy(report, copy);
            files.add(new ReportFile(name, sha256(copy), Files.size(copy)));
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            var doc = builder.parse(copy.toFile());
            if (!doc.getDocumentElement().getTagName().equals("testsuite")) throw new IllegalArgumentException("Unsupported JUnit report root");
            var cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                if (++tests > 10000) throw new IllegalArgumentException("Too many JUnit test cases");
                Element test = (Element) cases.item(i);
                if (test.getElementsByTagName("skipped").getLength() > 0) skipped++;
                var failed = test.getElementsByTagName("failure");
                if (failed.getLength() > 0) {
                    failures++;
                    Element failure = (Element) failed.item(0);
                    if (details.size() < 100) details.add(new Failure(test.getAttribute("classname"), test.getAttribute("name"),
                        failure.getAttribute("type"), shortText(failure.getAttribute("message"))));
                }
                if (test.getElementsByTagName("error").getLength() > 0) errors++;
            }
        }
        return new Reports(tests, failures, errors, skipped, List.copyOf(details), List.copyOf(files));
    }
    public static Reports empty() { return new Reports(0, 0, 0, 0, List.of(), List.of()); }

    public static Outcome classify(String state, boolean oomKilled, Integer exitCode,
        boolean compileOnly, String selectedTest, String stdout, String stderr, Reports reports) {
        if (state.equals("CANCELLED")) return Outcome.CANCELLED;
        if (state.equals("TIMED_OUT")) return Outcome.TIMED_OUT;
        if (state.equals("ERROR")) return Outcome.INFRASTRUCTURE_ERROR;
        if (oomKilled) return Outcome.RESOURCE_LIMIT;
        if (exitCode == null) return Outcome.INFRASTRUCTURE_ERROR;
        if (exitCode == 0) {
            if (compileOnly) return Outcome.PASSED;
            if (reports.tests() - reports.skipped() < 1 || reports.failures() > 0 || reports.errors() > 0) return Outcome.INFRASTRUCTURE_ERROR;
            if (selectedTest != null && reports.files().stream().noneMatch(f -> f.name().equals("TEST-" + selectedTest + ".xml") || f.name().endsWith("." + selectedTest + ".xml"))) return Outcome.INFRASTRUCTURE_ERROR;
            return Outcome.PASSED;
        }
        String output = stdout + "\n" + stderr;
        // Conservative Maven compiler signature. Exit 1 or arbitrary text alone is insufficient.
        if (output.contains("COMPILATION ERROR") && output.contains("maven-compiler-plugin") && output.contains("Compilation failure"))
            return Outcome.COMPILATION_FAILED;
        if (reports.errors() > 0) return Outcome.INFRASTRUCTURE_ERROR;
        boolean expectedAssertion = reports.details().stream().anyMatch(f ->
            (selectedTest == null || f.className().equals(selectedTest) || f.className().endsWith("." + selectedTest)) &&
            (f.type().equals("org.opentest4j.AssertionFailedError") || f.type().equals("java.lang.AssertionError") || f.type().equals("org.junit.ComparisonFailure")));
        if (reports.failures() > 0 && expectedAssertion && !compileOnly) return Outcome.ASSERTION_FAILED;
        return Outcome.INFRASTRUCTURE_ERROR;
    }
    public static void write(Path evidence, Map<String, Object> metadata) throws Exception {
        var mapper = JsonMapper.builder().build();
        Path temporary = evidence.resolve("execution.json.tmp");
        Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
        Files.move(temporary, evidence.resolve("execution.json"), StandardCopyOption.ATOMIC_MOVE);
    }
    public static String read(Path path) throws Exception { return Files.readString(path, StandardCharsets.UTF_8); }
    public static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static String shortText(String value) { return value.substring(0, Math.min(value.length(), 2000)); }
}
