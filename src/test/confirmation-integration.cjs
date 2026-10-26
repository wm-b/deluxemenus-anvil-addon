const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {rcon, connect, parse, pause, clients, smoke} = require('./integration.cjs');

async function run() {
  // Prefer the user's supplied reproduction menu; otherwise use the example.
  const fixture = path.join(smoke, 'plugins/DeluxeMenus/gui_menus/confirmation_regression.yml');
  if (!fs.existsSync(fixture)) {
    const example = fs.readFileSync(path.join(smoke, 'plugins/DeluxeMenus/gui_menus/eventpoints_amount.yml'), 'utf8');
    fs.writeFileSync(fixture, example.replace(/^open_command:.*\r?\n/gm, '').replace(/^register_command:.*\r?\n/gm, ''));
  }
  const configFile = path.join(smoke, 'plugins/DeluxeMenus/config.yml');
  let config = fs.readFileSync(configFile, 'utf8');
  if (!config.includes('  confirmation_regression:')) {
    config = config.replace('gui_menus:', 'gui_menus:\n  confirmation_regression:\n    file: confirmation_regression.yml');
    fs.writeFileSync(configFile, config);
  }
  await rcon('dm reload');
  const client = await connect('OutputTester');
  await rcon('gamemode adventure OutputTester');
  let window;
  let output;
  const packets = [];
  client.on('open_window', packet => { window = packet.windowId; });
  client.on('window_items', packet => {
    if (packet.windowId === window) { output = packet.items[2]; packets.push({kind: 'all', packet}); }
  });
  client.on('set_slot', packet => {
    if (packet.windowId === window) {
      if (packet.slot === 2) output = packet.item;
      packets.push({kind: 'slot', packet});
    }
  });
  await rcon('dm open confirmation_regression OutputTester');
  await pause(300);
  assert.ok(output?.itemCount > 0, 'Configured confirmation head must initially be present');
  const configured = structuredClone(output);
  for (const text of ['1', '123', 'invalid', '', '25', '']) {
    packets.length = 0;
    // Vanilla AnvilScreen recalculates the result locally when its text changes.
    // Protocol clients do not run that UI code, so reproduce its cleared result.
    output = {itemCount: 0};
    client.write('name_item', {name: text});
    await pause(200);
    assert.ok(packets.some(entry => entry.kind === 'slot' && entry.packet.slot === 2),
      'Missing corrective output-slot packet after input: ' + JSON.stringify(text));
    assert.deepEqual(output, configured, 'Confirmation texture/name/lore must be preserved');
    assert.ok(!packets.some(entry => entry.kind === 'all' || entry.packet.slot === 0),
      'Do not resend the input slot or full inventory, which resets the text field');
    assert.equal(await parse('OutputTester', '%deluxemenusanviladdon_text%'), text);
  }
  // Rapid edits must settle on the latest text and still receive a correction.
  packets.length = 0;
  for (const text of ['1', '12', '123', '1234']) client.write('name_item', {name: text});
  await pause(200);
  assert.deepEqual(output, configured);
  assert.equal(await parse('OutputTester', '%deluxemenusanviladdon_text%'), '1234');
  assert.ok(packets.some(entry => entry.kind === 'slot' && entry.packet.slot === 2));
  // A queued correction must not leak into a different inventory after closing.
  client.write('name_item', {name: 'closing'});
  client.write('close_window', {windowId: window});
  await pause(200);
  assert.equal(await parse('OutputTester', '%deluxemenusanviladdon_text%'), '');
  console.log('PASS: confirmation restored after typing and clearing, custom head metadata retained, text preserved, rapid edits and close handling.');
}

run().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  for (const client of clients) client.end('Confirmation integration test finished');
  await pause(300);
});
