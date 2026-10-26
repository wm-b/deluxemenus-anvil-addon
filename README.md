# DeluxeMenusAnvilAddon

An anvil text add-on for DeluxeMenus and PlaceholderAPI. It has no commands
or configuration of its own. Menu items, requirements and actions stay in
DeluxeMenus YAML.

Targets Paper 1.21.1, DeluxeMenus 1.14.1, PlaceholderAPI 2.12.3 and Java 21.
Java sources use `inceris.deluxeanvil` under `src/main/java/inceris/deluxeanvil`.

## Build

Use the included Gradle 8.12.1 wrapper with Java 21:

```powershell
.\gradlew.bat clean build
```

On Linux or macOS, use `./gradlew clean build`.

The JAR is `build/libs/DeluxeMenuAnvilAddon_1-0-0.jar`.

## Install

Copy the JAR into the Paper server's `plugins` directory alongside DeluxeMenus
and PlaceholderAPI, then restart.

Every DeluxeMenus menu with `inventory_type: ANVIL` is detected automatically.
Define the left input item in slot 0 and the output item in slot 2.

## Empty initial input

The vanilla anvil initializes its text field from the first item's visible
name. A server-only add-on cannot independently keep a name such as
`Instructions` on that item and use a different default in the field.

For an empty input, use a formatting-only name and put instructions in lore:

```yaml
items:
  input:
    material: PAPER
    slot: 0
    display_name: '&r'
    lore:
      - '&e&lInstructions'
      - '&7Type the amount in the field above.'
```

The reset code preserves a custom name whose plain text is empty, so the
field starts with zero characters. The add-on's text placeholder also starts
empty. Instructions remain visible when hovering over the input item.

## Confirmation validation

Add this optional section at the top level of a DeluxeMenus anvil menu:

```yaml
anvil_input_regex:
  expression: '[A-Za-z0-9_]{3,16}'
  error-message: '&cUse 3-16 letters, numbers or underscores.'
```

Omitting `anvil_input_regex` preserves normal DeluxeMenus behaviour. An explicitly
empty `expression` accepts only empty input. A configured section requires a
string `expression`; a missing expression blocks confirmation.

## Placeholders

| Placeholder | Value |
| --- | --- |
| `%deluxemenusanviladdon_text%` | Current text in the viewer's open DeluxeMenus anvil, or empty outside one. |
| `%deluxemenusanviladdon_submitted_text%` | Text captured on the last output-slot click. |
| `%deluxemenusanviladdon_submitted_integer%` | Captured text normalized to a signed 32-bit integer; `0` for invalid or overflowing input. |
| `%deluxemenusanviladdon_viewer%` | Player name, without requiring the PlaceholderAPI Player expansion. |

Raw text is player input. Apply appropriate requirements to commands that use
it. Numeric commands can use `submitted_integer` and a numeric requirement:

```yaml
menu_title: 'Choose a number'
inventory_type: ANVIL
open_command: numberinput
items:
  input:
    material: PAPER
    slot: 0
    display_name: '1'
  confirm:
    material: PAPER
    slot: 2
    display_name: '&aConfirm'
    left_click_requirement:
      requirements:
        positive:
          type: '>'
          input: '%deluxemenusanviladdon_submitted_integer%'
          output: '0'
          deny_commands:
            - '[message] &cEnter a positive whole number.'
    left_click_commands:
      - '[message] &aYou chose %deluxemenusanviladdon_submitted_integer%.'
      - '[close]'
```
