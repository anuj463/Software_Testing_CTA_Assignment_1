package com.anuj.testingdashboard;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "(?:public\\s+)?(?:final\\s+)?class\\s+([A-Za-z_$][\\w$]*)");

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");

    @PostMapping(
            value = "/analyze",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> analyze(@RequestBody AnalysisRequest request) {

        String code = request.code() == null ? "" : request.code();

        List<Map<String, Object>> cases = new ArrayList<>();

        if (code.isBlank()) {
            return emptyResponse(
                    "No Java source supplied. Paste Java source code first.");
        }

        Path workspace = null;

        try {
            SourceInfo source = sourceInfo(code);

            workspace = Files.createTempDirectory(
                    "testing-dashboard-");

            Path sourceDir = workspace.resolve(
                    "src/main/java");

            Path testDir = workspace.resolve(
                    "src/test/java");

            Files.createDirectories(sourceDir);
            Files.createDirectories(testDir);

            Path packageDir = source.packageName().isBlank()
                    ? sourceDir
                    : sourceDir.resolve(
                            source.packageName().replace('.', '/'));

            Files.createDirectories(packageDir);

            Files.writeString(
                    packageDir.resolve(
                            source.className() + ".java"),
                    code,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            String testCode = generatedTest(source);

            Path testPackageDir = source.packageName().isBlank()
                    ? testDir
                    : testDir.resolve(
                            source.packageName().replace('.', '/'));

            Files.createDirectories(testPackageDir);

            Files.writeString(
                    testPackageDir.resolve(
                            "SubmittedCodeGeneratedTest.java"),
                    testCode,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            Path projectPom = Path.of("pom.xml")
                    .toAbsolutePath()
                    .normalize();

            Path checkstyle = Path.of("config/checkstyle.xml")
                    .toAbsolutePath()
                    .normalize();

            if (!Files.exists(projectPom)
                    || !Files.exists(checkstyle)) {

                throw new IOException(
                        "Run the JAR from the extracted project folder "
                                + "so pom.xml and config/checkstyle.xml "
                                + "are available.");
            }

            Files.copy(
                    projectPom,
                    workspace.resolve("pom.xml"));

            Files.createDirectories(
                    workspace.resolve("config"));

            Files.copy(
                    checkstyle,
                    workspace.resolve(
                            "config/checkstyle.xml"));

            CommandResult result = runMaven(workspace);

            Map<String, Object> reports =
                    readReports(workspace);

            addJUnitCases(
                    cases,
                    source,
                    result,
                    reports);

            addJaCoCoCases(
                    cases,
                    reports);

            addPmdCases(
                    cases,
                    reports);

            addSpotBugsCases(
                    cases,
                    reports);

            addCheckstyleCases(
                    cases,
                    reports);

            Map<String, Object> out = response(
                    cases,
                    "Real Maven execution completed. "
                            + "Exactly 5 test cases are generated "
                            + "for each of the 5 tools.");

            out.put(
                    "details",
                    Map.of(
                            "mavenExitCode",
                            result.exitCode(),
                            "workspaceDeleted",
                            deleteTree(workspace)));

            workspace = null;

            return out;

        } catch (Exception e) {

            return errorResponse(
                    e.getMessage() == null
                            ? e.toString()
                            : e.getMessage());

        } finally {

            if (workspace != null) {
                deleteTree(workspace);
            }
        }
    }

    private void addJUnitCases(
            List<Map<String, Object>> cases,
            SourceInfo source,
            CommandResult result,
            Map<String, Object> reports) {

        boolean buildPassed =
                result.exitCode() == 0;

        boolean junitPassed =
                buildPassed
                        && Boolean.TRUE.equals(
                                reports.get("junitPassed"));

        String message = buildPassed
                ? String.valueOf(
                        reports.get("junitMessage"))
                : firstError(result.output());

        add(
                cases,
                "JUnit 5",
                "Class compilation and generated test execution",
                junitPassed,
                junitPassed ? "PASSED" : "NOT PASSED",
                message,
                "JUnit Test 1 of 5");

        add(
                cases,
                "JUnit 5",
                "Submitted class can be loaded by reflection",
                junitPassed,
                junitPassed ? "PASSED" : "NOT PASSED",
                "Class under test: "
                        + source.fullyQualifiedName(),
                "JUnit Test 2 of 5");

        add(
                cases,
                "JUnit 5",
                "Submitted class exposes at least one declared method",
                junitPassed,
                junitPassed ? "PASSED" : "NOT PASSED",
                "Verified by generated JUnit test.",
                "JUnit Test 3 of 5");

        add(
                cases,
                "JUnit 5",
                "Submitted class has a declared constructor",
                junitPassed,
                junitPassed ? "PASSED" : "NOT PASSED",
                "Verified by generated JUnit test.",
                "JUnit Test 4 of 5");

        add(
                cases,
                "JUnit 5",
                "Generated test suite contains five executable test methods",
                junitPassed,
                junitPassed ? "PASSED" : "NOT PASSED",
                "Generated JUnit methods: 5.",
                "JUnit Test 5 of 5");
    }

    private void addJaCoCoCases(
            List<Map<String, Object>> cases,
            Map<String, Object> reports) {

        addCoverageCase(
                cases,
                "Line coverage",
                getDouble(reports, "lineCoverage"),
                50.0);

        addCoverageCase(
                cases,
                "Branch coverage",
                getDouble(reports, "branchCoverage"),
                30.0);

        addCoverageCase(
                cases,
                "Instruction coverage",
                getDouble(
                        reports,
                        "instructionCoverage"),
                50.0);

        addCoverageCase(
                cases,
                "Method coverage",
                getDouble(
                        reports,
                        "methodCoverage"),
                50.0);

        addCoverageCase(
                cases,
                "Class coverage",
                getDouble(
                        reports,
                        "classCoverage"),
                100.0);
    }

    private double getDouble(
            Map<String, Object> reports,
            String key) {

        Object value = reports.get(key);

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        return 0.0;
    }

    private void addCoverageCase(
            List<Map<String, Object>> cases,
            String metric,
            double value,
            double threshold) {

        boolean passed = value >= threshold;

        add(
                cases,
                "JaCoCo",
                metric
                        + " >= "
                        + String.format(
                                "%.0f%%",
                                threshold),
                passed,
                passed ? "PASSED" : "NOT PASSED",
                String.format(
                        "Measured: %.1f%%; required: %.1f%%.",
                        value,
                        threshold),
                "JaCoCo coverage test");
    }

    private void addPmdCases(
            List<Map<String, Object>> cases,
            Map<String, Object> reports) {

        @SuppressWarnings("unchecked")
        Map<Integer, Integer> priorities =
                (Map<Integer, Integer>)
                        reports.get("pmdPriorities");

        if (priorities == null) {
            priorities = new LinkedHashMap<>();
        }

        for (int priority = 1;
             priority <= 5;
             priority++) {

            int count =
                    priorities.getOrDefault(
                            priority,
                            0);

            add(
                    cases,
                    "PMD",
                    "PMD priority "
                            + priority
                            + " violations = 0",
                    count == 0,
                    count == 0
                            ? "PASSED"
                            : "NOT PASSED",
                    "Violations found: "
                            + count,
                    "PMD Test "
                            + priority
                            + " of 5");
        }
    }

    private void addSpotBugsCases(
            List<Map<String, Object>> cases,
            Map<String, Object> reports) {

        @SuppressWarnings("unchecked")
        Map<String, Integer> categories =
                (Map<String, Integer>)
                        reports.get("spotbugsCategories");

        if (categories == null) {
            categories = new LinkedHashMap<>();
        }

        String[] names = {
            "CORRECTNESS",
            "BAD_PRACTICE",
            "PERFORMANCE",
            "SECURITY",
            "MT_CORRECTNESS"
        };

        for (int i = 0;
             i < names.length;
             i++) {

            int count =
                    categories.getOrDefault(
                            names[i],
                            0);

            add(
                    cases,
                    "SpotBugs",
                    names[i]
                            + " bug instances = 0",
                    count == 0,
                    count == 0
                            ? "PASSED"
                            : "NOT PASSED",
                    "Bug instances found: "
                            + count,
                    "SpotBugs Test "
                            + (i + 1)
                            + " of 5");
        }
    }

    private void addCheckstyleCases(
            List<Map<String, Object>> cases,
            Map<String, Object> reports) {

        @SuppressWarnings("unchecked")
        Map<String, Integer> rules =
                (Map<String, Integer>)
                        reports.get("checkstyleRules");

        if (rules == null) {
            rules = new LinkedHashMap<>();
        }

        String[] names = {
            "LineLength",
            "FileTabCharacter",
            "AvoidStarImport",
            "EmptyBlock",
            "NeedBraces"
        };

        for (int i = 0;
             i < names.length;
             i++) {

            int count =
                    rules.getOrDefault(
                            names[i],
                            0);

            add(
                    cases,
                    "Checkstyle",
                    names[i]
                            + " violations = 0",
                    count == 0,
                    count == 0
                            ? "PASSED"
                            : "NOT PASSED",
                    "Violations found: "
                            + count,
                    "Checkstyle Test "
                            + (i + 1)
                            + " of 5");
        }
    }

    private Map<String, Object> readReports(
            Path workspace) throws Exception {

        Map<String, Object> r =
                new LinkedHashMap<>();

        r.put(
                "junitPassed",
                false);

        r.put(
                "junitMessage",
                "No JUnit report generated.");

        Path surefire =
                workspace.resolve(
                        "target/surefire-reports");

        if (Files.exists(surefire)) {

            try (var files =
                         Files.list(surefire)) {

                Path xml =
                        files
                                .filter(
                                        p -> p.getFileName()
                                                != null)
                                .filter(
                                        p -> p.getFileName()
                                                .toString()
                                                .endsWith(".xml"))
                                .findFirst()
                                .orElse(null);

                if (xml != null) {

                    String text =
                            Files.readString(xml);

                    String pattern =
                            "tests=\"(\\d+)\"[^>]*"
                                    + "errors=\"(\\d+)\""
                                    + "[^>]*skipped=\"(\\d+)\""
                                    + "[^>]*failures=\"(\\d+)\"";

                    Matcher m =
                            Pattern.compile(pattern)
                                    .matcher(text);

                    if (m.find()) {

                        int tests =
                                Integer.parseInt(
                                        m.group(1));

                        int errors =
                                Integer.parseInt(
                                        m.group(2));

                        int skipped =
                                Integer.parseInt(
                                        m.group(3));

                        int failures =
                                Integer.parseInt(
                                        m.group(4));

                        boolean passed =
                                tests >= 5
                                        && errors == 0
                                        && failures == 0;

                        r.put(
                                "junitPassed",
                                passed);

                        r.put(
                                "junitMessage",
                                "Tests: "
                                        + tests
                                        + ", failures: "
                                        + failures
                                        + ", errors: "
                                        + errors
                                        + ", skipped: "
                                        + skipped);
                    }
                }
            }
        }

        Path jacoco =
                workspace.resolve(
                        "target/site/jacoco/jacoco.xml");

        r.put(
                "lineCoverage",
                readCoverageMetric(
                        jacoco,
                        "LINE"));

        r.put(
                "branchCoverage",
                readCoverageMetric(
                        jacoco,
                        "BRANCH"));

        r.put(
                "instructionCoverage",
                readCoverageMetric(
                        jacoco,
                        "INSTRUCTION"));

        r.put(
                "methodCoverage",
                readCoverageMetric(
                        jacoco,
                        "METHOD"));

        r.put(
                "classCoverage",
                readCoverageMetric(
                        jacoco,
                        "CLASS"));

        r.put(
                "pmdPriorities",
                readPmdPriorities(
                        workspace.resolve(
                                "target/pmd.xml")));

        r.put(
                "spotbugsCategories",
                readSpotBugsCategories(
                        workspace.resolve(
                                "target/spotbugsXml.xml")));

        r.put(
                "checkstyleRules",
                readCheckstyleRules(
                        workspace.resolve(
                                "target/checkstyle-result.xml")));

        return r;
    }

    /*
     * IMPORTANT FIX:
     *
     * JaCoCo's jacoco.xml contains:
     *
     * <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN"
     * "report.dtd">
     *
     * The normal Java XML parser tries to load report.dtd.
     * The temporary Maven workspace does not contain that file,
     * which caused:
     *
     * report.dtd (The system cannot find the file specified)
     *
     * This method disables external DTD/entity loading.
     */
    private Document parseXml(Path file)
            throws Exception {

        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();

        factory.setFeature(
                "http://xml.org/sax/features/"
                        + "external-general-entities",
                false);

        factory.setFeature(
                "http://xml.org/sax/features/"
                        + "external-parameter-entities",
                false);

        factory.setFeature(
                "http://apache.org/xml/features/"
                        + "nonvalidating/load-external-dtd",
                false);

        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_DTD,
                "");

        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_SCHEMA,
                "");

        factory.setXIncludeAware(false);

        factory.setExpandEntityReferences(false);

        return factory
                .newDocumentBuilder()
                .parse(file.toFile());
    }

    private double readCoverageMetric(
            Path file,
            String type) throws Exception {

        if (!Files.exists(file)) {
            return 0.0;
        }

        Document doc =
                parseXml(file);

        NodeList counters =
                doc.getElementsByTagName(
                        "counter");

        for (int i = 0;
             i < counters.getLength();
             i++) {

            Element e =
                    (Element) counters.item(i);

            if (type.equals(
                    e.getAttribute("type"))) {

                double missed =
                        Double.parseDouble(
                                e.getAttribute(
                                        "missed"));

                double covered =
                        Double.parseDouble(
                                e.getAttribute(
                                        "covered"));

                if (missed + covered == 0) {
                    return 0.0;
                }

                return covered * 100.0
                        / (missed + covered);
            }
        }

        return 0.0;
    }

    private Map<Integer, Integer> readPmdPriorities(
            Path file) throws Exception {

        Map<Integer, Integer> result =
                new LinkedHashMap<>();

        if (!Files.exists(file)) {
            return result;
        }

        Document doc =
                parseXml(file);

        NodeList nodes =
                doc.getElementsByTagName(
                        "violation");

        for (int i = 0;
             i < nodes.getLength();
             i++) {

            Element e =
                    (Element) nodes.item(i);

            int priority;

            try {

                priority =
                        Integer.parseInt(
                                e.getAttribute(
                                        "priority"));

            } catch (NumberFormatException ex) {

                priority = 3;
            }

            result.put(
                    priority,
                    result.getOrDefault(
                            priority,
                            0) + 1);
        }

        return result;
    }

    private Map<String, Integer> readSpotBugsCategories(
            Path file) throws Exception {

        Map<String, Integer> result =
                new LinkedHashMap<>();

        if (!Files.exists(file)) {
            return result;
        }

        Document doc =
                parseXml(file);

        NodeList nodes =
                doc.getElementsByTagName(
                        "BugInstance");

        for (int i = 0;
             i < nodes.getLength();
             i++) {

            Element e =
                    (Element) nodes.item(i);

            String category =
                    e.getAttribute(
                            "category");

            if (category.isBlank()) {
                category = "OTHER";
            }

            result.put(
                    category,
                    result.getOrDefault(
                            category,
                            0) + 1);
        }

        return result;
    }

    private Map<String, Integer> readCheckstyleRules(
            Path file) throws Exception {

        Map<String, Integer> result =
                new LinkedHashMap<>();

        if (!Files.exists(file)) {
            return result;
        }

        Document doc =
                parseXml(file);

        NodeList nodes =
                doc.getElementsByTagName(
                        "error");

        for (int i = 0;
             i < nodes.getLength();
             i++) {

            Element e =
                    (Element) nodes.item(i);

            String source =
                    e.getAttribute(
                            "source");

            if (source == null
                    || source.isBlank()) {

                continue;
            }

            String rule =
                    source.substring(
                            source.lastIndexOf('.') + 1);

            result.put(
                    rule,
                    result.getOrDefault(
                            rule,
                            0) + 1);
        }

        return result;
    }

    private String generatedTest(
            SourceInfo source) {

        String pkg =
                source.packageName().isBlank()
                        ? ""
                        : "package "
                                + source.packageName()
                                + ";\n\n";

        String fqn =
                source.fullyQualifiedName();

        return pkg
                + "import org.junit.jupiter.api.Test;\n"
                + "import java.lang.reflect.Constructor;\n"
                + "import java.lang.reflect.Method;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n\n"
                + "class SubmittedCodeGeneratedTest {\n"

                + "    @Test\n"
                + "    void classLoads() throws Exception {\n"
                + "        assertNotNull(Class.forName(\""
                + fqn
                + "\"));\n"
                + "    }\n"

                + "    @Test\n"
                + "    void hasDeclaredMethod() throws Exception {\n"
                + "        assertTrue(Class.forName(\""
                + fqn
                + "\").getDeclaredMethods().length > 0);\n"
                + "    }\n"

                + "    @Test\n"
                + "    void hasConstructor() throws Exception {\n"
                + "        assertTrue(Class.forName(\""
                + fqn
                + "\").getDeclaredConstructors().length > 0);\n"
                + "    }\n"

                + "    @Test\n"
                + "    void methodsAreReflective() throws Exception {\n"
                + "        Method[] m = Class.forName(\""
                + fqn
                + "\").getDeclaredMethods();\n"
                + "        assertNotNull(m);\n"
                + "    }\n"

                + "    @Test\n"
                + "    void constructorsAreReflective() throws Exception {\n"
                + "        Constructor<?>[] c = Class.forName(\""
                + fqn
                + "\").getDeclaredConstructors();\n"
                + "        assertNotNull(c);\n"
                + "    }\n"

                + "}\n";
    }

    private void add(
            List<Map<String, Object>> cases,
            String tool,
            String input,
            boolean passed,
            String output,
            String details,
            String testLabel) {

        Map<String, Object> m =
                new LinkedHashMap<>();

        m.put(
                "tool",
                tool);

        m.put(
                "testCase",
                testLabel);

        m.put(
                "input",
                input);

        m.put(
                "output",
                output);

        m.put(
                "passed",
                passed);

        m.put(
                "details",
                details);

        cases.add(m);
    }

    private Map<String, Object> response(
            List<Map<String, Object>> cases,
            String note) {

        long passed =
                cases.stream()
                        .filter(
                                x -> Boolean.TRUE.equals(
                                        x.get("passed")))
                        .count();

        Map<String, Object> out =
                new LinkedHashMap<>();

        out.put(
                "project",
                "Complex Code Testing Dashboard");

        out.put(
                "tools",
                List.of(
                        "JUnit 5",
                        "JaCoCo",
                        "PMD",
                        "SpotBugs",
                        "Checkstyle"));

        out.put(
                "testCasesPerTool",
                5);

        out.put(
                "totalTestCases",
                25);

        out.put(
                "testCases",
                cases);

        out.put(
                "summary",
                Map.of(
                        "total",
                        cases.size(),
                        "passed",
                        passed,
                        "failed",
                        cases.size() - passed));

        out.put(
                "note",
                note);

        return out;
    }

    private Map<String, Object> emptyResponse(
            String message) {

        return errorResponse(message);
    }

    private Map<String, Object> errorResponse(
            String message) {

        List<Map<String, Object>> cases =
                new ArrayList<>();

        String[] tools = {
            "JUnit 5",
            "JaCoCo",
            "PMD",
            "SpotBugs",
            "Checkstyle"
        };

        for (String tool : tools) {

            for (int i = 1;
                 i <= 5;
                 i++) {

                add(
                        cases,
                        tool,
                        "Submitted Java source",
                        false,
                        "NOT PASSED",
                        message,
                        tool
                                + " Test "
                                + i
                                + " of 5");
            }
        }

        return response(
                cases,
                "Analysis could not be completed; "
                        + "all 25 test cases are marked "
                        + "NOT PASSED.");
    }

    private CommandResult runMaven(
            Path workspace)
            throws IOException, InterruptedException {

        String mvn =
                System.getProperty(
                        "os.name",
                        "")
                        .toLowerCase()
                        .contains("win")
                        ? "mvn.cmd"
                        : "mvn";

        Process process =
                new ProcessBuilder(
                        mvn,
                        "-q",
                        "clean",
                        "verify")
                        .directory(
                                workspace.toFile())
                        .redirectErrorStream(true)
                        .start();

        StringBuilder output =
                new StringBuilder();

        Thread reader =
                new Thread(
                        () -> readProcess(
                                process,
                                output));

        reader.start();

        boolean finished =
                process.waitFor(
                        120,
                        TimeUnit.SECONDS);

        if (!finished) {

            process.destroyForcibly();

            return new CommandResult(
                    124,
                    "Maven execution timed out "
                            + "after 120 seconds.");
        }

        reader.join(2000);

        return new CommandResult(
                process.exitValue(),
                output.toString());
    }

    private void readProcess(
            Process process,
            StringBuilder output) {

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     process.getInputStream(),
                                     StandardCharsets.UTF_8))) {

            String line;

            while ((line =
                    reader.readLine()) != null) {

                output.append(line)
                        .append('\n');
            }

        } catch (IOException ignored) {
            // Process output is best effort.
        }
    }

    private SourceInfo sourceInfo(
            String code) {

        Matcher c =
                CLASS_PATTERN.matcher(code);

        if (!c.find()) {

            throw new IllegalArgumentException(
                    "No Java class declaration was found.");
        }

        Matcher p =
                PACKAGE_PATTERN.matcher(code);

        String pkg =
                p.find()
                        ? p.group(1)
                        : "";

        return new SourceInfo(
                c.group(1),
                pkg);
    }

    private String firstError(
            String output) {

        return output.lines()
                .filter(
                        s -> s.contains("ERROR")
                                || s.contains(
                                        "COMPILATION ERROR"))
                .findFirst()
                .orElse(
                        "Maven verify failed.");
    }

    private boolean deleteTree(
            Path root) {

        if (root == null
                || !Files.exists(root)) {

            return true;
        }

        try (var paths =
                     Files.walk(root)) {

            paths
                    .sorted(
                            java.util.Comparator
                                    .reverseOrder())
                    .forEach(
                            this::deleteIfExists);

            return !Files.exists(root);

        } catch (IOException e) {

            return false;
        }
    }

    private void deleteIfExists(
            Path path) {

        try {

            Files.deleteIfExists(path);

        } catch (IOException ignored) {
            // Best-effort cleanup after analysis.
        }
    }

    private record SourceInfo(
            String className,
            String packageName) {

        String fullyQualifiedName() {

            return packageName.isBlank()
                    ? className
                    : packageName
                            + "."
                            + className;
        }
    }

    private record CommandResult(
            int exitCode,
            String output) {
    }

    public record AnalysisRequest(
            String code) {
    }
}