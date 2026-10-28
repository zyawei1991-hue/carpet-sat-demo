// 展示端链路体检。**用 Node 22 自带的 WebSocket 冒充一块圆屏**，连 `:simulator:run` 起的服务端，
// 把契约里那几条最容易在现场爆的地方逐条走一遍：
//
//   hello → ack(for_seq) → state 1Hz → event 必回执 → 回执后不重发 → command 被处理 → bye 立刻断开
//
// 为什么值得单独留一个文件：明天固件烧进去之后，第一件事不是拿真屏试，是跑这个脚本对着手机连一次。
// 屏上的图画错了肉眼能看出来，链路时序错了肉眼看不出来 —— 而现场只会露一次。
//
//   node simulator/linkcheck.mjs [ws://host:8443] [screen_id]

const URL_ = process.argv[2] ?? 'ws://127.0.0.1:8443';
const SCREEN_ID = process.argv[3] ?? 'WS175B-A3F7';

let seq = 0;
const out = [];
const fail = (m) => out.push('FAIL ' + m);
const ok = (m) => out.push('ok   ' + m);

const ws = new WebSocket(URL_);
const states = [];
const events = [];
const acks = [];
const closes = [];
let helloAcked = false;
let byeAt = null;

const send = (t, body) => ws.send(JSON.stringify({ v: 1, t, seq: seq++, ts: Math.floor(Date.now() / 1000), ...body }));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

ws.onopen = () => {
  ok('已连上 ' + URL_);
  // 首帧必须是 hello：服务端在拿到 screen_id 之前没法查白名单（D8）
  send('hello', { hello: { screen_id: SCREEN_ID, fw: 'linkcheck-1', protocols: [1] } });
};
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.t === 'state') states.push(m);
  if (m.t === 'ack') {
    acks.push(m);
    if (m.ack?.forSeq === 0 || m.ack?.for_seq === 0) helloAcked = true;
  }
  if (m.t === 'event') {
    const ev = m.event ?? {};
    events.push(ev);
    // 契约规定 event 必须回执，否则服务端会一直重发同一条
    send('ack', { ack: { for_seq: m.seq, for_event_id: ev.event_id } });
  }
};
ws.onclose = (e) => closes.push({ code: e.code, reason: e.reason, at: Date.now() });

const run = async () => {
  await sleep(3000);

  if (!helloAcked) fail('hello 之后没收到 ack(for_seq=0) —— 白名单或 accepting 总闸有问题');
  else ok('hello 已过白名单，服务端回执');

  // state 是 1Hz 的周期快照：3 秒里少于 3 帧就是没在推
  if (states.length < 3) fail(`3 秒只收到 ${states.length} 帧 state，周期快照没跑起来`);
  else ok(`state ${states.length} 帧 / 3 秒`);

  const need = ['link', 'sat', 'gnss', 'fence', 'sos'];
  const miss = need.filter((k) => !(k in (states.at(-1)?.state ?? {})));
  if (miss.length) fail('state 缺字段：' + miss.join(','));
  else ok('state 必填字段齐（link/sat/gnss/fence/sos）');

  const before = events.length;
  send('command', { command: { cmd: 'sos_press', args: {} } });
  await sleep(3500);
  if (events.length === before) fail('发了 sos_press，App 没有产出任何 event —— 指令那一段没接上');
  else ok(`sos_press 被处理，产生 ${events.length - before} 条 event：` + events.slice(before).map((e) => e.kind).join(','));

  const n2 = events.length;
  send('command', { command: { cmd: 'sos_cancel', args: {} } });
  await sleep(3500);
  const got = events.slice(n2).map((e) => e.kind);
  if (!got.includes('sos_cancelled')) fail('发了 sos_cancel，没等到 sos_cancelled');
  else ok('sos_cancel 走通，收到 sos_cancelled');

  const ids = events.map((e) => e.event_id);
  const dup = ids.filter((x, i) => ids.indexOf(x) !== i);
  if (dup.length) fail('回执之后仍重发了同一条 event：' + dup.join(','));
  else ok(`${ids.length} 条 event 无重发（回执生效）`);

  const lastSeq = seq;
  byeAt = Date.now();
  send('bye', {});
  await sleep(1500);
  if (!closes.length) fail('发了 bye，连接还挂着 —— 台账上那台屏会离线不及时');
  else ok(`bye 后 ${Date.now() - byeAt}ms 关闭（code=${closes[0].code}${closes[0].reason ? ' ' + closes[0].reason : ''}）`);

  console.log(out.join('\n'));
  console.log(out.some((l) => l.startsWith('FAIL')) ? '\n结论：链路有问题' : '\n结论：链路可用');
  process.exit(out.some((l) => l.startsWith('FAIL')) ? 1 : 0);
};

ws.onerror = (e) => {
  console.log('连不上 ' + URL_ + '：' + (e.message ?? e.error ?? 'unknown') + '\n先把驱动器起起来：./gradlew :simulator:run --args="--hold 6"');
  process.exit(1);
};

run();
