/* 临时探针：数 1Hz 快照到底有没有在推，以及 sos_press 之后会不会停 */
const sleep = ms => new Promise(r => setTimeout(r, ms));
const T0 = Date.now();
let n = 0, last = 0;
const ws = new WebSocket('ws://127.0.0.1:8443');
ws.onopen = () => {
  ws.send(JSON.stringify({v:2,t:'hello',seq:1,ts:Date.now(),hello:{screen_id:'WS175B-A3F7',fw:'probe',protocols:[1,2]}}));
  setTimeout(() => { console.log('-- sos_press'); ws.send(JSON.stringify({v:2,t:'command',seq:9,ts:Date.now(),command:{cmd:'sos_press',args:{}}})); }, 6000);
  setTimeout(() => { console.log('-- sos_cancel'); ws.send(JSON.stringify({v:2,t:'command',seq:10,ts:Date.now(),command:{cmd:'sos_cancel',args:{}}})); }, 12000);
  setInterval(() => { const t = Math.round((Date.now()-T0)/1000); console.log(`state ${n} 帧 (其中这 3 秒 ${n-last} 帧) @${t}s`); last = n; }, 3000);
};
ws.onmessage = e => {
  let m; try { m = JSON.parse(e.data); } catch { return; }
  if (m.t === 'state') n++;
  if (m.t === 'event') console.log('event', m.event.kind, JSON.stringify(m.event).slice(0,140));
  if (m.t === 'dialog') console.log('dialog', JSON.stringify(m.dialog).slice(0,120));
};
setTimeout(() => { ws.close(); process.exit(0); }, 24000);
