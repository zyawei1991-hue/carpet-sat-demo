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
  const n = {
    id, innerHTML: '', style: {}, attrs: {}, children: [], handlers, own: '',
    setAttribute(k, v) { this.attrs[k] = v; },
    appendChild(c) { this.children.push(c); return c; },
    addEventListener(ev, fn) { (handlers[ev] ||= []).push(fn); },
    getBoundingClientRect() { return {left: 0, top: 0, width: 466, height: 466}; },
  };
  // textContent 的赋值必须真的清空子节点：draw() 每帧先 ui.textContent=''，
  // 不清空的话断言读到的还是上一帧的字，"SOS 压过对话"这类测试永远测不出来
  Object.defineProperty(n, 'textContent', {
    get() { return n.children.length ? n.children.map((c) => c.textContent).join(' ') : n.own; },
    set(v) { n.children.length = 0; n.own = v == null ? '' : String(v); },
  });
  return (nodes[id] = n);
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
else ok(`hello 带 screen_id=${hello.hello.screen_id} fw=${hello.hello.fw} protocols=[${hello.hello.protocols}]`);
// 不申报 [2] 的话，服务端那条 dialog 通道根本不会往这台屏推（ScreenLinkServer 逐连接按版本过滤），
// 表现就是"手机答了，屏上什么都没有"
if (!hello.hello.protocols?.includes(2)) fail('hello 没申报契约 v2 —— 手机不会把对话推给这台屏');
else ok('hello 申报了契约 v2，对话通道才会开');

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

// ── 9. 对话（契约 v2）：屏是耳朵和嘴，手机是记录 ─────────────────────────────
const uiText = () => node('ui').textContent;
const countOf = (s) => uiText().split(s).length - 1;
const DLG = (turn_id, dir, text, extra = {}) => ({
  v: 2, t: 'dialog', seq: 900, ts: Math.floor(now / 1000),
  dialog: Object.assign({turn_id, dir, text, source: 'local_rule', origin: 'phone'}, extra),
});

handleFrame(JSON.stringify(STATE()));
sent.length = 0;
pointer('pointerdown', 233, 57);                 // 状态视图里那颗"按住问一句"
if (sent.some((m) => m.t === 'dialog')) fail('才按下去话就发出去了 —— "按住说、松开才发"这个动作没生效');
pointer('pointerup', 233, 57);
const up = sent.find((m) => m.t === 'dialog');
if (!up) fail('松开手指没发出 dialog 帧');
else if (up.v !== 2 || up.dialog.dir !== 'up' || up.dialog.origin !== 'screen' || up.dialog.source !== 'asr')
  fail('dialog 帧形状不对：' + JSON.stringify(up));
else ok(`按住说话 → dialog{dir:up, origin:screen, source:asr, turn_id=${up.dialog.turn_id}} 已发出`);
if (countOf('问 · 圆屏') === 0) fail('自己说出去的那句没上屏 —— 只有答没有问，用户读不出这是在答哪句');
else ok('自己那句当场画上，角标「问 · 圆屏」');

// 手机把这句镜像回来（同一 turn_id、同一 dir）：屏上不能出现两遍
const xid = up.dialog.turn_id;
handleFrame(JSON.stringify({v: 2, t: 'dialog', seq: 910, ts: Math.floor(now / 1000), dialog: Object.assign({}, up.dialog)}));
if (countOf('车到哪了') !== 1) fail(`镜像回来的同一条 up 没去重，屏上出现 ${countOf('车到哪了')} 遍`);
else ok('同 turn_id + dir 去重：手机回镜的那句自己那行不会画第二遍');

handleFrame(JSON.stringify(DLG(xid, 'down', '车在西湖景区，卫星链路正常')));
if (countOf('答 · 手机起') === 0) fail('手机答的那句话没上屏');
else ok('手机起头答的那句上了屏，角标写清「答 · 手机起」');

// 求救压过对话
handleFrame(JSON.stringify(STATE({sos: {level: 'sent', attempts: 1, sat_ack: true}})));
if (countOf('车在西湖景区') > 0) fail('SOS 弹层期间还把对话画在屏上 —— 那一屏只能有求救');
else ok('SOS 进行中对话不画，一屏一个重心');
sent.length = 0;
pointer('pointerdown', 233, 57);
pointer('pointerup', 233, 57);
if (sent.some((m) => m.t === 'dialog')) fail('SOS 弹层期间还能对着屏说话');
else ok('SOS 期间说话键不给按（那屏上任何"顺便问一句"的邀请都在分走注意力）');

handleFrame(JSON.stringify(STATE()));
// 小智官方云那一路：单独一档标注，而且过屏前先过一遍红线
// **手机的闸管不到小智直接往屏上画的那条路**，所以屏这一侧必须自己再守一次
handleFrame(JSON.stringify(DLG('x-1', 'down', '救援已在路上', {source: 'xiaozhi_cloud', origin: 'screen'})));
if (countOf('救援已在路上') > 0) fail('小智云那句越界的话原样上了屏 —— §6.1 红线一在屏这一侧漏了');
else if (countOf('不能替平台说') === 0) fail('越界句是拦下了，可屏上没给出替代文案');
else ok('小智云那句入账前先过闸：画出来的是「这句我不能替平台说，以手机台账为准」');
if (countOf('小智云 · 未过网关') === 0) fail('小智云那一路没单独标出来，观众会把它当成我们的模型在答');

// 折行不许把型号拆开：GT1005 折成 GT10/05 是这屏最难看的一种错，观众恰恰盯着型号看
const wrapped = ctx.wrapCjk('当前相位 locked，GT1005 在线。下一次过顶还有 128 秒。', 16);
if (wrapped.some((w) => /^[A-Za-z0-9]+$/.test(w.slice(-1) + (wrapped[wrapped.indexOf(w) + 1] || '')[0]) && /[A-Za-z0-9]$/.test(w) && /^[A-Za-z0-9]/.test(wrapped[wrapped.indexOf(w) + 1] || '')))
  fail('折行把一串字母数字切开了：' + JSON.stringify(wrapped));
else ok('折行按整词走：' + wrapped.join('⏎'));
const wrapped2 = ctx.wrapCjk('待发 0 条 / 槽位 50 条，已交核心网 0 条。', 16);
if (wrapped2.some((w) => /^[，。、）？！]/.test(w))) fail('标点开头的折行，读起来像上一句被腰斩：' + JSON.stringify(wrapped2));
else ok('逗号句号不会开新行：' + wrapped2.join('⏎'));

// 说完 25 秒自己回状态盘
advance(26_000);
if (countOf('小智云 · 未过网关') > 0) fail('25 秒没人说话没回到状态盘 —— 这块屏的日常身份是车载状态表');
else ok('25 秒静默 → 自动回状态盘');
if (countOf('长按 3 秒触发 SOS') === 0) fail('回到状态盘后 SOS 那颗键没画出来');

console.log(out.join('\n'));
console.log(out.some(l => l.startsWith('FAIL')) ? '\n结论：冒充件的链路有问题' : '\n结论：冒充件按契约在跑');
process.exit(out.some(l => l.startsWith('FAIL')) ? 1 : 0);
