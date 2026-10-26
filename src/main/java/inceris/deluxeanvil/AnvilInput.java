package inceris.deluxeanvil;

/** Shared by the network thread, server thread and PlaceholderAPI readers. */
final class AnvilInput {
    private volatile String text;
    private volatile String submitted = "";
    private long lastSubmission = Long.MIN_VALUE;

    AnvilInput(String initial) {
        text = initial;
    }

    String text() { return text; }
    String submitted() { return submitted; }

    void rename(String value) {
        // Match vanilla's maximum anvil name length. Do not execute or parse input.
        if (value != null && value.length() <= 50) text = value;
    }

    void submit(long tick) {
        // Keep the same snapshot while DeluxeMenus' immediate actions are queued.
        if (lastSubmission != Long.MIN_VALUE && tick - lastSubmission < 2) return;
        submitted = text;
        lastSubmission = tick;
    }

    static String integer(String value) {
        if (!value.matches("[+-]?[0-9]{1,10}")) return "0";
        try {
            return Integer.toString(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return "0";
        }
    }
}
