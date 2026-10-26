package inceris.deluxeanvil;

import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

record AnvilValidation(boolean enabled, Pattern pattern, String errorMessage) {
    static final String DEFAULT_ERROR = "&cThat input is not valid.";

    static AnvilValidation disabled() {
        return new AnvilValidation(false, null, DEFAULT_ERROR);
    }

    static AnvilValidation invalid(String message) {
        return new AnvilValidation(true, null, message);
    }

    static AnvilValidation compile(String expression, String message, Consumer<String> reportError) {
        if (expression == null) return disabled();
        try {
            return new AnvilValidation(true, Pattern.compile(expression), message);
        } catch (PatternSyntaxException error) {
            reportError.accept("Invalid anvil_input_regex.expression: " + error.getDescription());
            return invalid(message);
        }
    }

    boolean matches(String value) {
        return !enabled || pattern != null && pattern.matcher(value).matches();
    }
}
