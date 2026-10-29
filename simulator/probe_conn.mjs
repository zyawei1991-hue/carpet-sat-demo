/* 只连只断、不发任何 command：看 1Hz 是不是被连接本身拖停 */
const sleep = ms => new Promise(r => setTimeout(r, ms));
for (let round = 1; round <= 6; round++) {
  let n = 0;
  const ws = new WebSocket('ws://127.0.0.1:8443');
  await new Promise(res => { ws.onopen = res; });
  ws.send(JSON.stringify({v:2,t:'hello',seq:1,ts:Date.now(),hello:{screen_id:'WS175B-A3F7',fw:'probe',protocols:[1,2]}}));
  ws.onmessage = e => { try { const m = JSON.parse(e.data); if (m.t === 'state') n++; } catch {} };
  await sleep(3000);
  console.log(`round ${round}: 3 秒 ${n} 帧`);
  ws.send(JSON.stringify({v:2,t:'bye',seq:99,ts:Date.now()}));
  await sleep(1500);
}
process.exit(0);
