import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TestDuff {

    private static final String FILE_EXT = ".c";
    private static final String INVALID_ENDING = ".invalid" + FILE_EXT;
    private static final String DEVALID_ENDING = ".devalid" + FILE_EXT;

    private static final String CONFIRMATION_TEXT = "DUFF: Can unroll!";
    private static final String FAILURE_TEXT = "DUFF: Cannot unroll!";

    private static String getCparserLoc() {
        return System.getenv("LOC_CPARSER").trim();
    }

    private static String getTestCaseLoc() {
        return System.getenv("LOC_TEST_CASES").trim();
    }

    private static boolean isValid(File file) {
        return file.getName().endsWith(FILE_EXT) && !file.getName().endsWith(INVALID_ENDING);
    }

    private static boolean isInvalid(File file) {
        return file.getName().endsWith(INVALID_ENDING) || file.getName().endsWith(DEVALID_ENDING);
    }

    private static void runAndExpect(File file, String expect) throws IOException {
        File tempFileOriginal = File.createTempFile(String.format("duff-throw-away"), ".out");
        setPermissions(tempFileOriginal);
        tempFileOriginal.deleteOnExit();
        var stdErr = Runtime.getRuntime().exec(
            new String[] {getCparserLoc(), "-fno-inline", "-funroll-loops", "-o", tempFileOriginal.getAbsolutePath(),
                file.getAbsolutePath()}, new String[] {"FIRMDBG=setmask firm.opt.loop-unrolling -1"})
            .getErrorStream();
        var reader = new BufferedReader(new InputStreamReader(stdErr));
        if (reader.lines().peek(System.out::println).noneMatch(line -> line.contains(expect))) {
            Assertions.fail();
        }
    }

    private static void setPermissions(File file) throws IOException {
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OTHERS_EXECUTE);
        perms.add(PosixFilePermission.OTHERS_READ);
        perms.add(PosixFilePermission.OTHERS_WRITE);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.OWNER_READ);
        Files.setPosixFilePermissions(file.toPath(), perms);
    }

    private static void runAssertEqual(File file, int duff_factor) throws IOException, InterruptedException {
        File tempFileOriginal = File.createTempFile(String.format("duff-test-factor-%d-", duff_factor), "original.out");
        File tempFileUnrolled = File.createTempFile(String.format("duff-test-factor-%d-", duff_factor), "unrolled.out");
        setPermissions(tempFileOriginal);
        setPermissions(tempFileUnrolled);
        Stream.of(tempFileOriginal, tempFileUnrolled).map(f -> f.setExecutable(true)).forEach(Assertions::assertTrue);
        Stream.of(tempFileOriginal, tempFileUnrolled).forEach(File::deleteOnExit);
        var originalCompile = Runtime.getRuntime().exec(new String[] {getCparserLoc(), "-O0", "-o", tempFileOriginal.getAbsolutePath(),
            file.getAbsolutePath()});
        var unrollCompile = Runtime.getRuntime().exec(new String[] {getCparserLoc(), "-fno-inline", "-funroll-loops", "-o", tempFileUnrolled.getAbsolutePath(),
            file.getAbsolutePath()}, new String[] {String.format("DUFF_FACTOR=%d", duff_factor)});

        boolean originalCompileInf = !originalCompile.waitFor(10, TimeUnit.SECONDS);
        boolean unrolledCompileInf = !unrollCompile.waitFor(10, TimeUnit.SECONDS);

        Assertions.assertFalse(originalCompileInf || unrolledCompileInf, "Compiler ran into infinite loop");
        unrollCompile.destroy();
        unrollCompile.waitFor();
        Assertions.assertEquals(unrollCompile.exitValue(), 0, "Compile failed!");


        var originalRun = Runtime.getRuntime().exec(new String[] {tempFileOriginal.getAbsolutePath()});
        var unrolledRun = Runtime.getRuntime().exec(new String[] {tempFileUnrolled.getAbsolutePath()});

        boolean originalInf = !originalRun.waitFor(10, TimeUnit.SECONDS);
        boolean unrolledInf = !unrolledRun.waitFor(10, TimeUnit.SECONDS);
        Assertions.assertEquals(originalInf, unrolledInf, String.format("Incorrect infinite behavior original was %sinfinite; unrolled was %sinfinite!", originalInf ? "" : "not ", unrolledInf ? "" : "not "));

        if (originalInf) {
            originalRun.destroy();
            originalRun.waitFor();
        }
        if (unrolledInf) {
            unrolledRun.destroy();
            unrolledRun.waitFor();
        }

        int originalCode = originalRun.exitValue();
        int unrolledCode = unrolledRun.exitValue();
        Assertions.assertEquals(originalCode, unrolledCode);

        if (originalRun.exitValue() == 0) {
            var originalReader = new BufferedReader(new InputStreamReader(originalRun.getInputStream()));
            var unrolledReader = new BufferedReader(new InputStreamReader(unrolledRun.getInputStream()));
            String originalOutput = originalReader.lines().collect(Collectors.joining());
            String unrolledOutput = unrolledReader.lines().collect(Collectors.joining());
            Assertions.assertEquals(originalOutput, unrolledOutput, String.format("Incorrect Output:\nOriginal (length %d):\n%s\nUnrolled (length %d):\n%s\n", originalOutput.length(), originalOutput, unrolledOutput.length(), unrolledOutput));
        }
    }

    private static void runAndExpectValid(File file) throws IOException {
        runAndExpect(file, CONFIRMATION_TEXT);
    }

    private static void runAndExpectInvalid(File file) throws IOException {
        runAndExpect(file, FAILURE_TEXT);
    }

    private Stream<File> getTestCases() {
        return Stream.of(new File(getTestCaseLoc()).listFiles());
    }

    @TestFactory
    public Stream<DynamicNode> createCorrectnessTest() {
        return getTestCases().
            filter(TestDuff::isValid)
            .map(file -> DynamicTest.dynamicTest(String.format("factor_%d-%s", 4, file.getName()), () -> runAssertEqual(file, 4)));
    }

    @TestFactory
    public Stream<DynamicNode> createCorrectnessTestAllFactors() {
        return getTestCases().
            filter(TestDuff::isValid)
            .flatMap(file -> IntStream.range(2, 32)
                .mapToObj(i -> DynamicTest.dynamicTest(String.format("factor_%d-%s", i, file.getName()), () -> runAssertEqual(file, i)))
            );
    }

    @TestFactory
    public Stream<DynamicNode> createValidTests() {
        return getTestCases()
            .filter(TestDuff::isValid)
            .map(file -> DynamicTest.dynamicTest(file.getName(), () -> runAndExpectValid(file)));
    }

    @TestFactory
    public Stream<DynamicNode> createInvalidTests() {
        return getTestCases()
            .filter(TestDuff::isInvalid)
            .map(file -> DynamicTest.dynamicTest(file.getName(), () -> runAndExpectInvalid(file)));
    }
}
