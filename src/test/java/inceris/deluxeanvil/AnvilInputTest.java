package inceris.deluxeanvil;

public final class AnvilInputTest {
    @org.junit.jupiter.api.Test
    void validatesInputAndSnapshots() {
        AnvilInput input = new AnvilInput("Initial");
        equal("Initial", input.text());
        equal("", input.submitted());
        input.rename("hello world");
        input.submit(100);
        input.rename("new text");
        input.submit(101);
        equal("hello world", input.submitted());
        equal("new text", input.text());
        input.submit(102);
        equal("new text", input.submitted());
        input.rename("x".repeat(51));
        equal("new text", input.text());
        input.rename("");
        equal("", input.text());
        for (String invalid : new String[] {"", "1.5", "1e3", "1 cmd", " 1", "1\n", "2147483648", "-2147483649", "%placeholder%"}) {
            equal("0", AnvilInput.integer(invalid));
        }
        for (String valid : new String[] {"0", "1", "-1", "+1", "001", "2147483647", "-2147483648"}) {
            equal(Integer.toString(Integer.parseInt(valid)), AnvilInput.integer(valid));
        }
        System.out.println("PASS: text, snapshots, empty/long input, numeric bounds and malformed command input.");
    }

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
