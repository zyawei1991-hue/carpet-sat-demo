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
const dialogs = [];
const acks = [];
const closes = [];
let helloAcked = false;
let byeAt = null;

const send = (t, body) => ws.send(JSON.stringify({ v: 1, t, seq: seq++, ts: Math.floor(Date.now() / 1000), ...body }));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

ws.onopen = () => {
  ok('已连上 ' + URL_);
  // 首帧必须是 hello：服务端在拿到 screen_id 之前没法查白名单（D8）
  // 申报 [1,2]：不带上 2，服务端那条 dialog 通道根本不会往这台屏推（逐连接按协商结果过滤）
  send('hello', { hello: { screen_id: SCREEN_ID, fw: 'linkcheck-2', protocols: [1, 2] } });
};
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.t === 'state') states.push(m);
  if (m.t === 'ack') {
    acks.push(m);
    if (m.ack?.forSeq === 0 || m.ack?.for_seq === 0) helloAcked = true;
  }
  if (m.t === 'dialog') dialogs.push(m.dialog);
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
  const cancels = events.slice(n2).filter((e) => e.kind === 'sos_cancelled' || e.kind === 'sos_failed');
  // 撤销**允许**被拒：脚本化彩排在某些相位里报文已经交了核心网、或者模组正好离线，那时"撤不了"是正确答案。
  // 真正的事故只有两种：按下去石沉大海（屏上那个「撤销这次 SOS」永远转圈，§3.1 的撤销承诺就空了），
  // 或者只回一句"失败"不给原因 —— 现场没人能对着"失败"两个字解释观众
  // ⚠️ `already_handed` 缺席 = false：宿主按 `encodeDefaults=false` 编码，等于默认值的字段根本不上线（和对话那一路同一件事）
  if (!cancels.length) fail('发了 sos_cancel，一条回执 event 都没回来 —— 撤销石沉大海');
  else if (cancels[0].kind === 'sos_cancelled') ok('sos_cancel 走通，收到 sos_cancelled');
  else if ((cancels[0].payload ?? cancels[0]).sat_result) {
    const why = cancels[0].payload ?? cancels[0];
    ok(`sos_cancel 被拒但给出了原因：sat_result=${why.sat_result} already_handed=${why.already_handed === true}`);
  }
  else fail('sos_cancel 回的是 sos_failed 且没带 sat_result —— 撤销失败却说不清为什么');

  const ids = events.map((e) => e.event_id);
  const dup = ids.filter((x, i) => ids.indexOf(x) !== i);
  if (dup.length) fail('回执之后仍重发了同一条 event：' + dup.join(','));
  else ok(`${ids.length} 条 event 无重发（回执生效）`);

  // ── 对话通道（契约 v2）。这条必须在真链路上验：
  //    协商错、逐连接过滤写反、回镜没接上 —— 三种错的现场表现都是"屏上说话没反应"，肉眼分不出来
  // 每次跑一个新的 turn_id：**宿主的记录跨连接去重**，复用固定 id 会被当成重复那句话直接丢掉
  const TID = 'lc-' + Date.now().toString(36);
  send('dialog', { dialog: { turn_id: TID, dir: 'up', text: '链路现在怎么样', source: 'asr', origin: 'screen' } });
  await sleep(2500);
  const mine = dialogs.filter((d) => d.turn_id === TID);
  if (!mine.length) fail('发了 dialog{dir:up}，服务端一条对话都没回 —— 对话通道没接上，或协商把这台屏挡掉了');
  else if (!mine.some((d) => d.dir === 'down')) fail('对话只收到镜像回来的自己那句，没有答：' + JSON.stringify(mine));
  else {
    const ans = mine.find((d) => d.dir === 'down');
    if (mine.filter((d) => d.dir === 'down').length !== 1) fail(`一轮答了 ${mine.length} 条，屏上就要多出一轮`);
    ok(`对话往返通：答「${ans.text.slice(0, 24)}」 source=${ans.source ?? 'local_rule(缺省)'} origin=${ans.origin ?? 'screen(缺省)'}`);
    // 沿用它那个 turn_id 才钉得回原来那一轮；另起一个 id 屏上就会多出第二条对话
    if (ans.turn_id !== TID) fail(`回镜没沿用屏那边的 turn_id，实际 ${ans.turn_id}`);
    else ok('回镜沿用屏那边的 turn_id');
    // 屏自己那句**不回镜**（回一遍就是同一句话在屏上出现两次）。所以这一轮只该收到一条 down
    const ups = mine.filter((d) => d.dir === 'up').length;
    if (ups) fail(`服务端把屏自己说的那句又镜像回来了 ${ups} 遍，屏上会出现两条一样的问话`);
    else ok('屏自己那句没被回镜，只有答那一条下来');
  }

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
