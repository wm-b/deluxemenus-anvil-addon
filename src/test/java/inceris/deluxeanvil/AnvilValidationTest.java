package inceris.deluxeanvil;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AnvilValidationTest {
    @Test void matchesTheWholeEnteredText() {
        var validation = AnvilValidation.compile("[A-Z]{2}-[0-9]{3}", "Try AB-123", error -> fail(error));
        assertTrue(validation.matches("AB-123"));
        for (String value : new String[] {"ab-123", " AB-123", "AB-123x", "xAB-123", "AB-123\n", ""}) {
            assertFalse(validation.matches(value), value);
        }
        assertEquals("Try AB-123", validation.errorMessage());
    }

    @Test void missingRegexPreservesExistingMenus() {
        var validation = AnvilValidation.compile(null, "unused", error -> fail(error));
        assertFalse(validation.enabled());
        assertTrue(validation.matches("any input"));
        assertTrue(validation.matches(""));
    }

    @Test void malformedRegexBlocksRatherThanDisablingValidation() {
        var errors = new ArrayList<String>();
        var validation = AnvilValidation.compile("[", "Bad input", errors::add);
        assertEquals(1, errors.size());
        assertTrue(validation.enabled());
        assertFalse(validation.matches("anything"));
        assertFalse(validation.matches(""));
    }

    @Test void emptyRegexIsAnExplicitEmptyInputRule() {
        var validation = AnvilValidation.compile("", "Only empty input", error -> fail(error));
        assertTrue(validation.enabled());
        assertTrue(validation.matches(""));
        assertFalse(validation.matches("text"));
    }
}
