const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {rcon, connect, parse, pause, clients, smoke} = require('./integration.cjs');
const menus = path.join(smoke, 'plugins/DeluxeMenus/gui_menus');
const definitions = {
  regex_general: {regex: '[A-Z]{2}-[0-9]{3}', error: '&cRegex rejected: %deluxemenusanviladdon_submitted_text%', common: true},
  regex_specific: {regex: '[A-Z]{2}-[0-9]{3}', error: '&cSpecific rejected', common: false},
  regex_malformed: {regex: '[', common: true},
  regex_missing: {missing: true, common: true},
  regex_empty: {regex: '', common: true}
};
const variants = [
  ['left', 0, 0], ['right', 0, 1], ['shift left', 1, 0], ['shift right', 1, 1], ['middle', 3, 2]
];

function writeMenu(name, definition) {
  let yaml = `menu_title: 'Regex validation test'
open_command: ${name}
inventory_type: ANVIL
anvil_input_regex:
`;
  if (!definition.missing) yaml += `  expression: '${definition.regex}'\n`;
  if (definition.error) yaml += `  error-message: '${definition.error}'\n`;
  else if (definition.missing) yaml += "  error-message: '&cMissing expression rejected'\n";
  yaml += `items:
  input:
    material: PAPER
    slot: 0
    display_name: 'Initial'
  back:
    material: ARROW
    slot: 1
    display_name: 'Back'
    left_click_commands:
      - '[message] Back button works'
  output:
    material: PAPER
    slot: 2
    display_name: 'Confirm'
`;
  const handlers = definition.common ? ['click'] : ['left_click', 'right_click', 'shift_left_click', 'shift_right_click', 'middle_click'];
  for (const handler of handlers) {
    yaml += `    ${handler}_commands:\n      - '[console] scoreboard players add %deluxemenusanviladdon_viewer% RegexActions 1'\n`;
    if (definition.common) yaml += "      - '[close]'\n";
  }
  if (!definition.common) yaml += `    left_click_requirement:
      requirements:
        existing:
          type: string equals
          input: '%deluxemenusanviladdon_submitted_text%'
          output: 'AB-123'
      deny_commands:
        - '[message] Native requirement denied'
`;
  fs.writeFileSync(path.join(menus, name + '.yml'), yaml);
}

async function open(client, name) {
  client.write('chat_command', {command: name});
  await pause(200);
}
async function rename(client, text) {
  client.write('name_item', {name: text});
  await pause(110);
}
async function click(client, mode = 0, button = 0, slot = 2) {
  client.write('window_click', {windowId: client.window, stateId: 0, slot, mouseButton: button,
    mode, changedSlots: [], cursorItem: {itemCount: 0}});
  await pause(260);
}
async function score(name, expected) {
  assert.match(await rcon(`scoreboard players get ${name} RegexActions`), new RegExp(`has ${expected} `));
}

async function run() {
  const configFile = path.join(smoke, 'plugins/DeluxeMenus/config.yml');
  let config = fs.readFileSync(configFile, 'utf8');
  for (const [name, definition] of Object.entries(definitions)) {
    writeMenu(name, definition);
    if (!config.includes(`  ${name}:`)) config = config.replace('gui_menus:', `gui_menus:\n  ${name}:\n    file: ${name}.yml`);
  }
  fs.writeFileSync(configFile, config);
  await rcon('dm reload');
  await rcon('scoreboard objectives add RegexActions dummy');
  for (const name of ['RegexTester', 'RegexTesterTwo']) await rcon(`scoreboard players set ${name} RegexActions 0`);
  const a = await connect('RegexTester');
  const b = await connect('RegexTesterTwo');
  const messages = [];
  a.on('system_chat', packet => messages.push(JSON.stringify(packet.content)));
  await rcon('gamemode creative RegexTester');
  await rcon('gamemode adventure RegexTesterTwo');
  await open(a, 'regex_general');
  await open(b, 'regex_general');
  await rename(a, 'invalid');
  await rename(b, 'AB-123');
  for (const [name, mode, button] of variants) {
    const before = messages.length;
    await click(a, mode, button);
    await score('RegexTester', 0);
    assert.equal(await parse('RegexTester', '%deluxemenusanviladdon_text%'), 'invalid', name + ' must leave menu open');
    assert.ok(messages.slice(before).some(message => message.includes('Regex rejected: invalid')), name + ' must show configured error');
  }
  await click(b);
  await score('RegexTesterTwo', 1);
  await score('RegexTester', 0);
  await rename(a, 'AB-123');
  await click(a);
  await score('RegexTester', 1);
  assert.equal(await parse('RegexTester', '%deluxemenusanviladdon_text%'), '');

  await open(a, 'regex_specific');
  await rename(a, 'invalid');
  for (const [name, mode, button] of variants) {
    const before = messages.length;
    await click(a, mode, button);
    await score('RegexTester', 1);
    assert.ok(messages.slice(before).some(message => message.includes('Specific rejected')), name + ' specific action must be blocked');
  }
  // Regex passes but an existing native requirement still fails, including its deny message.
  await rename(a, 'CD-456');
  const beforeNative = messages.length;
  await click(a);
  await score('RegexTester', 1);
  assert.ok(messages.slice(beforeNative).some(message => message.includes('Native requirement denied')));
  await rename(a, 'AB-123');
  let expected = 1;
  for (const [, mode, button] of variants) {
    await click(a, mode, button);
    await score('RegexTester', ++expected);
  }
  // Validation only gates the output slot; back actions must still work on invalid text.
  await rename(a, 'invalid');
  const beforeBack = messages.length;
  await click(a, 0, 0, 1);
  assert.ok(messages.slice(beforeBack).some(message => message.includes('Back button works')));

  for (const name of ['regex_malformed', 'regex_missing']) {
    await open(a, name);
    await rename(a, 'AB-123');
    const before = messages.length;
    await click(a);
    await score('RegexTester', expected);
    assert.equal(await parse('RegexTester', '%deluxemenusanviladdon_text%'), 'AB-123');
    assert.ok(messages.slice(before).some(message => message.includes(name === 'regex_missing' ? 'Missing expression rejected' : 'That input is not valid.')));
  }
  await open(a, 'regex_empty');
  await rename(a, 'not empty');
  await click(a);
  await score('RegexTester', expected);
  await rename(a, '');
  await click(a);
  await score('RegexTester', ++expected);

  // A new DeluxeMenus menu object after reload must load the changed expression.
  writeMenu('regex_general', {regex: 'Z+', error: '&cChanged expression rejected', common: true});
  await rcon('dm reload regex_general');
  await open(a, 'regex_general');
  await rename(a, 'AB-123');
  await click(a);
  await score('RegexTester', expected);
  await rename(a, 'ZZZ');
  await click(a);
  await score('RegexTester', ++expected);
  console.log('PASS: nested settings, general and five specific click actions, messages/placeholders, player isolation, native requirements/denials, back actions, malformed/missing/empty expressions and menu reload.');
}

run().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  for (const client of clients) client.end('Regex integration test finished');
  await pause(300);
});
