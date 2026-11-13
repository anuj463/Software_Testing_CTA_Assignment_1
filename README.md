# Complex Code Testing Dashboard

A Spring Boot + Maven dashboard for demonstrating exactly five software testing/quality tools against submitted Java source code.

## Tools and test cases

Each tool generates exactly **5 test cases**, giving **25 test cases total**:

1. JUnit 5 — 5 generated reflection/compilation tests
2. JaCoCo — 5 coverage tests: line, branch, instruction, method and class coverage
3. PMD — 5 tests, one for each PMD priority level (1–5)
4. SpotBugs — 5 tests for correctness, bad practice, performance, security and multithreading categories
5. Checkstyle — 5 tests for LineLength, FileTabCharacter, AvoidStarImport, EmptyBlock and NeedBraces

The submitted source is compiled and analyzed in a temporary Maven workspace. The workspace is deleted after analysis.

## Run

From the project root:

```powershell
mvn clean verify
java -jar target\code-testing-dashboard-1.0.0.jar
```

Then open http://localhost:8080.

This is intended for local academic/demo use. It executes submitted Java code through Maven and is not a hardened security sandbox.
