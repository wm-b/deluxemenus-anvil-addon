// Requires the disposable Paper server described in README.md, never the live lobby.
const assert = require('node:assert/strict');
const net = require('node:net');
const fs = require('node:fs');
const path = require('node:path');
const mc = require(process.env.MC_PROTOCOL_MODULE || '../../build/integration/node_modules/minecraft-protocol');
const smoke = process.env.ANVIL_TEST_SERVER || path.resolve(__dirname, '../../build/smoke-server');
const clients = [];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

async function rcon(command) {
  const socket = net.connect(25580, '127.0.0.1');
  await new Promise((resolve, reject) => { socket.once('connect', resolve); socket.once('error', reject); });
  let incoming = Buffer.alloc(0);
  const waiting = new Map();
  socket.on('data', data => {
    incoming = Buffer.concat([incoming, data]);
    while (incoming.length >= 4 && incoming.length >= incoming.readInt32LE(0) + 4) {
      const size = incoming.readInt32LE(0);
      const packet = incoming.subarray(4, size + 4);
      incoming = incoming.subarray(size + 4);
      const callback = waiting.get(packet.readInt32LE(0));
      if (callback && packet.readInt32LE(4) !== 0) callback(packet.subarray(8, -2).toString());
      else if (callback && packet.readInt32LE(0) === 2) callback(packet.subarray(8, -2).toString());
    }
  });
  function request(id, type, text) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('RCON timeout: ' + text)), 5000);
      waiting.set(id, result => { clearTimeout(timer); waiting.delete(id); resolve(result); });
      const body = Buffer.from(text);
      const packet = Buffer.alloc(body.length + 14);
      packet.writeInt32LE(body.length + 10, 0);
      packet.writeInt32LE(id, 4); packet.writeInt32LE(type, 8);
      body.copy(packet, 12); socket.write(packet);
    });
  }
  try {
    await request(1, 3, 'deluxemenusanviladdon-isolated-test');
    return await request(2, 2, command);
  } finally { socket.end(); }
}

async function connect(username) {
  const client = mc.createClient({host: '127.0.0.1', port: 25579, username, auth: 'offline', version: '1.21.1'});
  clients.push(client);
  client.on('error', error => console.error(username, error));
  client.on('kick_disconnect', packet => console.error('Kicked', username, packet));
  client.on('position', packet => client.write('teleport_confirm', {teleportId: packet.teleportId}));
  client.on('open_window', packet => { client.window = packet.windowId; });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Login timeout for ' + username)), 15000);
    client.once('position', () => { clearTimeout(timer); resolve(); });
  });
  return client;
}

async function parse(player, placeholder) {
  const output = await rcon(`papi parse ${player} ${placeholder}`);
  return output.replace(/\u001b\[[0-9;]*m/g, '').replace(/\u00a7[0-9a-fk-or]/gi, '').trim();
}

async function run() {
  const dmConfig = path.join(smoke, 'plugins/DeluxeMenus/config.yml');
  let config = fs.readFileSync(dmConfig, 'utf8');
  if (!config.includes('  integration_input:')) {
    fs.writeFileSync(dmConfig, config.replace('gui_menus:', 'gui_menus:\n  integration_input:\n    file: integration_input.yml'));
  }
  fs.writeFileSync(path.join(smoke, 'plugins/DeluxeMenus/gui_menus/integration_input.yml'), `menu_title: 'Input test'
open_command: inputtest
inventory_type: ANVIL
items:
  input:
    material: PAPER
    slot: 0
    display_name: 'Initial'
  output:
    material: PAPER
    slot: 2
    display_name: 'Confirm'
    left_click_commands:
      - '[close]'
      - '[console] scoreboard players set %deluxemenusanviladdon_viewer% AnvilTest %deluxemenusanviladdon_submitted_integer%'
`);
  // Exercise the real menu requirements without issuing an RCC transfer.
  const amountMenu = path.join(smoke, 'plugins/DeluxeMenus/gui_menus/eventpoints_amount.yml');
  fs.writeFileSync(amountMenu, fs.readFileSync(amountMenu, 'utf8').replace(
    'transferpoints %deluxemenusanviladdon_viewer% cobblemon %deluxemenusanviladdon_submitted_integer%',
    'scoreboard players add %deluxemenusanviladdon_viewer% AnvilFlow %deluxemenusanviladdon_submitted_integer%'));
  console.log(await rcon('dm reload'));
  console.log(await rcon('scoreboard objectives add AnvilTest dummy'));
  await rcon('scoreboard objectives add AnvilFlow dummy');
  await rcon('scoreboard players set AnvilTester AnvilFlow 0');
  const a = await connect('AnvilTester');
  const b = await connect('AnvilTesterTwo');
  await rcon('gamemode adventure AnvilTester');
  await rcon('gamemode adventure AnvilTesterTwo');
  a.write('chat_command', {command: 'inputtest'});
  b.write('chat_command', {command: 'inputtest'});
  await pause(500);
  assert.ok(a.window && b.window, 'Both anvil windows must open');
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_text%'), 'Initial');
  a.write('name_item', {name: '123'});
  b.write('name_item', {name: 'Other player'});
  await pause(150);
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_text%'), '123');
  assert.equal(await parse('AnvilTesterTwo', '%deluxemenusanviladdon_text%'), 'Other player');
  a.write('window_click', {windowId: a.window, stateId: 0, slot: 2, mouseButton: 0,
    mode: 0, changedSlots: [], cursorItem: {itemCount: 0}});
  await pause(250);
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_submitted_text%'), '123');
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_text%'), '');
  a.write('close_window', {windowId: a.window});
  await pause(150);
  a.write('chat_command', {command: 'eventpoints'});
  await pause(200);
  a.write('window_click', {windowId: a.window, stateId: 0, slot: 22, mouseButton: 0,
    mode: 0, changedSlots: [], cursorItem: {itemCount: 0}});
  await pause(250);
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_text%'), '');
  for (const invalid of ['0', '-1', '1.5', '2147483648', '1 extra']) {
    a.write('name_item', {name: invalid});
    await pause(100);
    a.write('window_click', {windowId: a.window, stateId: 0, slot: 2, mouseButton: 0,
      mode: 0, changedSlots: [], cursorItem: {itemCount: 0}});
    await pause(150);
    assert.match(await rcon('scoreboard players get AnvilTester AnvilFlow'), /has 0 /);
    assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_text%'), invalid);
  }
  a.write('name_item', {name: '7'});
  await pause(100);
  const confirmation = {windowId: a.window, stateId: 0, slot: 2, mouseButton: 0,
    mode: 0, changedSlots: [], cursorItem: {itemCount: 0}};
  a.write('window_click', confirmation);
  a.write('window_click', confirmation);
  await pause(250);
  assert.match(await rcon('scoreboard players get AnvilTester AnvilFlow'), /has 7 /);
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_text%'), '');
  assert.match(await rcon('scoreboard players get AnvilTester AnvilTest'), /123/);
  assert.match(await rcon('data get entity AnvilTester Inventory'), /\[\]/);
  // Closing a second player's menu must not submit text or affect the first player.
  b.write('close_window', {windowId: b.window});
  await pause(150);
  assert.equal(await parse('AnvilTesterTwo', '%deluxemenusanviladdon_submitted_text%'), '');
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_submitted_text%'), '7');
  a.write('chat_command', {command: 'inputtest'});
  await pause(250);
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_submitted_text%'), '');
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_text%'), 'Initial');
  a.write('name_item', {name: ''});
  await pause(100);
  assert.equal(await parse('AnvilTester', '%deluxemenusanviladdon_text%'), '');
  assert.equal(fs.existsSync(path.join(smoke, 'plugins/DeluxeMenusAnvilAddon')), false, 'Add-on must not create a config folder');
  console.log('PASS: initial text, rename packets, player isolation, click snapshot, command after close, no leaked items, cancellation, reset, empty input, no config, server selector, invalid amounts and double-click protection.');
}

module.exports = {rcon, connect, parse, pause, clients, smoke};

if (require.main === module) {
  run().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
    for (const client of clients) client.end('Integration test finished');
    await pause(300);
  });
}
