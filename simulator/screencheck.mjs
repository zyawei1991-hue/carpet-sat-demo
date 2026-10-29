// 冒充件的链路自检。**不启动浏览器**：把 screen.html 里那段 <script> 抽出来，
// 塞进一个假 DOM + 假 WebSocket + 假时钟的 vm context 里跑，然后按契约逐条断言。
//
// 为什么值得这么绕一遍：linkcheck.mjs 证明的是"服务端那侧时序对"，它用的是自己写的客户端。
// 而圆屏到货当天真正连上手机的是 screen.html 这块浏览器冒充件 —— 它回执不回执、
// 长按三秒到底发不发得出 command，肉眼对着静止画面是看不出来的。
//
//   node simulator/screencheck.mjs
//
// 契约依据：protocol/screen-link.md + screen-link.schema.json

import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';

const html = readFileSync(fileURLToPath(new URL('./screen.html', import.meta.url)), 'utf8');
const src = html.split('<script>')[1].split('</script>')[0];

const out = [];
const ok = (m) => out.push('ok   ' + m);
const fail = (m) => out.push('FAIL ' + m);

// ── 假时钟：长按那 3 秒必须能推进，又不能真等 ────────────────────────────────
let now = 1_795_200_000_000;
let timers = [];
let tid = 1;
const sleepTimers = [];
function advance(ms) {
  const target = now + ms;
  let guard = 0;
  while (now < target && guard++ < 5000) {
    const due = nextDue();
    // 推进到下一个到期点；没有定时器就直接跳到终点
    now = due == null || due > target ? target : Math.max(due, now + 1);
    fireDue();
  }
  now = target;
}
function nextDue() {
  const d = timers.map(t => t.at).concat(sleepTimers.map(t => t.at)).sort((a, b) => a - b);
  return d[0];
}
function fireDue() {
  for (const t of timers.filter(x => x.at <= now)) { t.fn(); t.at = now + t.every; }   // 跑完再改期
  for (const t of sleepTimers.filter(x => x.at <= now)) { t.fn(); sleepTimers.splice(sleepTimers.indexOf(t), 1); }
}

// ── 假 DOM：只实现 screen.html 真正用到的那几个动作 ─────────────────────────
const sent = [];
const sockets = [];
class FakeWebSocket {
  constructor(url) {
    this.url = url; this.readyState = 1; sockets.push(this);
    // 真 WebSocket 建好会回调 onopen，重连之后也要能再连上，所以这里也得给一次
    sleepTimers.push({fn: () => this.onopen && this.onopen(), at: now + 50});
  }
  send(s) { sent.push(JSON.parse(s)); }
  close() { this.readyState = 3; this.onclose && this.onclose(); }
}
const nodes = {};
function node(id) {
  if (nodes[id]) return nodes[id];
  const handlers = {};
  return (nodes[id] = {
    id, textContent: '', innerHTML: '', style: {}, attrs: {}, children: [], handlers,
    setAttribute(k, v) { this.attrs[k] = v; },
    appendChild(c) { this.children.push(c); return c; },
    addEventListener(ev, fn) { (handlers[ev] ||= []).push(fn); },
    getBoundingClientRect() { return {left: 0, top: 0, width: 466, height: 466}; },
  });
}
const winHandlers = {};
class FakeDate extends Date { static now() { return now; } }
const ctx = {
  console, JSON, Math, Date: FakeDate, URLSearchParams,
  document: {getElementById: node, createElementNS: (ns, tag) => node('svg:' + tag + ':' + Math.random())},
  window: {addEventListener: (ev, fn) => { (winHandlers[ev] ||= []).push(fn); }},
  WebSocket: FakeWebSocket,
  location: {search: '', href: 'http://127.0.0.1:8091/'},
  setInterval: (fn, ms) => { const t = {fn, at: now + ms, every: ms}; timers.push(t); return t; },
  clearInterval: (t) => { const i = timers.indexOf(t); if (i >= 0) timers.splice(i, 1); },
  setTimeout: (fn, ms) => { const t = {fn, at: now + ms}; sleepTimers.push(t); return t; },
  clearTimeout: (t) => { const i = sleepTimers.indexOf(t); if (i >= 0) sleepTimers.splice(i, 1); },
  fetch: () => ({then: () => ({catch: () => {}})}),
};
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext(src, ctx, {filename: 'screen.html'});

// 顶层 function 声明会挂到 context 上，测试直接照契约调它们
const {handleFrame, sendCmd} = ctx;
const ws = sockets[0];
const pointer = (type, x, y) => node('screen').handlers[type]?.forEach(f => f({clientX: x, clientY: y}));
const cmds = () => sent.filter(m => m.t === 'command').map(m => m.command.cmd);
const acks = () => sent.filter(m => m.t === 'ack');

const STATE = (over = {}) => ({
  v: 1, t: 'state', seq: (STATE.n = (STATE.n || 0) + 1), ts: Math.floor(now / 1000),
  state: Object.assign({
    link: {primary: 'cell', cell_ok: true, sat_ok: false},
    sat: {phase: 'idle', module_ok: true},
    gnss: {lat: 30.2741, lon: 120.1551, source: 'phone', fix_age_s: 1},
    fence: {active: false, inside: true},
    sos: {level: 'normal', attempts: 0},
    phone_battery: 78, demo_mode: false,
  }, over),
});

// ── 1. 建链：首帧必须是 hello，且带白名单要的 screen_id 和 protocols ─────────
advance(100);
const hello = sent.find(m => m.t === 'hello');
if (!hello || hello.hello.screen_id !== 'WS175B-A3F7' || !hello.hello.protocols?.includes(1))
  fail('hello 帧不对：白名单要 screen_id + protocols 含 1 —— ' + JSON.stringify(hello));
else ok(`hello 带 screen_id=${hello.hello.screen_id} fw=${hello.hello.fw} protocols=[1]`);

// ── 2. state：seq 单调，旧帧丢弃 ─────────────────────────────────────────────
handleFrame(JSON.stringify(STATE()));
handleFrame(JSON.stringify(STATE({sos: {level: 'normal', attempts: 0}})));   // 同 seq 重放
const drawn = node('ui').children.length;
if (drawn === 0) fail('一帧 state 都没画上');
else ok(`state 已画（svg 里 ${drawn} 个节点），旧 seq 不重画`);

// ── 3. event 必须回执，且回执指名道姓 ────────────────────────────────────────
const ev = {v: 1, t: 'event', seq: 41, event: {kind: 'sat_visible', event_id: 'e-41', payload: {}}};
handleFrame(JSON.stringify(ev));
const a = acks().find(x => x.ack.for_event_id === 'e-41');
if (!a) fail('收到 event 没回 ack —— 服务端会 3 秒重发一次、再 3 秒放弃');
else if (a.ack.for_seq !== 41) fail(`ack.for_seq 应该是事件帧的 seq 41，实际 ${a.ack.for_seq}`);
else ok('event 已回执，for_seq/for_event_id 都对得上');

// ── 4. 长按 3 秒才发 sos_press，中途松手就取消 ───────────────────────────────
pointer('pointerdown', 233, 411);
advance(2000);
if (cmds().includes('sos_press')) fail('才按 2 秒就发了 sos_press —— 倒计时没起作用');
pointer('pointerup', 233, 411);
advance(3000);
if (cmds().includes('sos_press')) fail('松手之后倒计时没停，SOS 还是发出去了');
else ok('中途松手：2 秒时没发、松手后也没补发（§3.1 那条防误触的倒计时是真在起作用）');

pointer('pointerdown', 233, 411);
advance(3400);
if (!cmds().includes('sos_press')) fail('长按满 3 秒没发出 sos_press');
else ok('长按满 3 秒 → command{sos_press} 已发出');

// ── 5. 已经发出去了，同一块区域就变成撤销 ────────────────────────────────────
handleFrame(JSON.stringify(STATE({sos: {level: 'sent', attempts: 1, sat_ack: true}})));
sent.length = 0;
pointer('pointerdown', 233, 411);
if (!cmds().includes('sos_cancel')) fail('SOS 进行中按那块区没发出 sos_cancel');
else ok('SOS 进行中 → 点一下就是 command{sos_cancel}（撤销上屏，不用回手机）');

// 非按钮区不许误触
sent.length = 0;
pointer('pointerdown', 233, 100);
advance(3400);
if (cmds().length) fail('按在轨迹盘外面也触发了指令：' + cmds().join(','));
else ok('触摸热区只有底下那块按钮，按在别处不会触发');

// ── 6. bye：关页面要立刻道别，否则手机台账上那台屏迟迟不变离线 ───────────────
sent.length = 0;
(winHandlers['pagehide'] || []).forEach(f => f());
if (!sent.some(m => m.t === 'bye')) fail('页面关掉时没发 bye');
else ok('关页面发 bye');

// ── 7. 断线：3 秒没状态打灰、10 秒主动重连，且不许黑屏 ───────────────────────
const before = sockets.length;
handleFrame(JSON.stringify(STATE()));
advance(4000);
if (!/没更新/.test(node('hint').textContent)) fail('4 秒没状态，提示没变：' + node('hint').textContent);
else ok('3 秒没状态 → 提示与外圈打灰，最后一帧保留（' + node('hint').textContent + '）');
advance(13000);   // 10 秒判重连，再留够它 2 秒退避 + 建链的时间
if (sockets.length <= before) fail('10 秒没状态没触发重连');
else ok(`10 秒没状态 → 主动重连，第 ${sockets.length} 条连接已建`);

// ── 8. 文案红线：屏上不许出现那几句（§6.1 红线一）────────────────────────────
const banned = ['已送达', '发送成功', '送达成功', '救援已在路上', '平台已受理', '一定没问题'];
const drawnText = node('ui').children.map(c => c.textContent).join('|');
const hit = banned.filter(b => drawnText.includes(b));
if (hit.length) fail('屏上画出了禁用句：' + hit.join(','));
else ok(`画出来的 ${node('ui').children.length} 段文字里没有禁用句（tx_ok 那档现在叫「星上已应答」）`);

console.log(out.join('\n'));
console.log(out.some(l => l.startsWith('FAIL')) ? '\n结论：冒充件的链路有问题' : '\n结论：冒充件按契约在跑');
process.exit(out.some(l => l.startsWith('FAIL')) ? 1 : 0);
