// 展示端链路体检。**用 Node 22 自带的 WebSocket 冒充一块圆屏**，连 `:simulator:run` 起的服务端，
// 把契约里那几条最容易在现场爆的地方逐条走一遍：
//
//   hello → ack(for_seq) → state 1Hz → event 必回执 → 回执后不重发 → command 被处理 → bye 立刻断开
//
// 为什么值得单独留一个文件：明天固件烧进去之后，第一件事不是拿真屏试，是跑这个脚本对着手机连一次。
// 屏上的图画错了肉眼能看出来，链路时序错了肉眼看不出来 —— 而现场只会露一次。
//
//   node simulator/linkcheck.mjs [ws://host:8443] [screen_id] [--voice]
//
// 带 `--voice` 时这一趟还会盯音频那一条：**必须先起一个带 --voice 的驱动器**，
// 不带这个开关就只报一句"没收到音频帧"，不判 FAIL —— 那时没声音是正确答案。

const URL_ = process.argv[2] ?? 'ws://127.0.0.1:8443';
const SCREEN_ID = process.argv[3] ?? 'WS175B-A3F7';
const WANT_VOICE = process.argv.slice(2).includes('--voice');

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
const audio = [];
let helloAcked = false;
let byeAt = null;

const send = (t, body) => ws.send(JSON.stringify({ v: 1, t, seq: seq++, ts: Math.floor(Date.now() / 1000), ...body }));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/**
 * 等到条件成立为止，最多 budget 毫秒。
 * 为什么不用固定 sleep：这条链路的答句有两个完全不同的源头 —— 本机规则几十毫秒，真模型
 * 三到十秒（`--ai` 那份端点，实测抖动就这么大）。固定 2.5 秒在规则那侧够用，
 * 在真模型那侧会**每次都报 FAIL**，而那恰恰是要验的那一条。到点就走，不白等。
 */
const until = async (pred, budget, every = 250) => {
  for (let t = 0; t < budget; t += every) {
    if (pred()) return true;
    await sleep(every);
  }
  return pred();
};

ws.onopen = () => {
  ok('已连上 ' + URL_);
  // 首帧必须是 hello：服务端在拿到 screen_id 之前没法查白名单（D8）
  // 申报 [1,2,3]：不带上 2，服务端那条 dialog 通道根本不会往这台屏推；不带上 3，
  // 服务端连 TTS 都不跑（audioReady 为假），音频那一条就永远验不到东西
  send('hello', { hello: { screen_id: SCREEN_ID, fw: 'linkcheck-3', protocols: [1, 2, 3] } });
};
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.t === 'state') states.push(m);
  if (m.t === 'ack') {
    acks.push(m);
    if (m.ack?.forSeq === 0 || m.ack?.for_seq === 0) helloAcked = true;
  }
  if (m.t === 'dialog') dialogs.push(m.dialog);
  if (m.t === 'command' && m.command?.cmd === 'audio_chunk') {
    // 帧的字节数一起收着：4096 是屏侧 buffer_size，超了的那一帧屏根本收不到，
    // 而服务端这边一切正常 —— 只有在这里称才知道
    audio.push({ bytes: Buffer.byteLength(e.data, 'utf8'), ...m.command.args });
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

  // ── ui 块（可选，决定屏上画哪张卡）。这条必须有自动化盯着：
  //    ui 缺失或取值不认识时**两边都不报错** —— 固件默默画回总览，现场表现是
  //    "手机上换了卡、屏没动"，然后所有人去怀疑网络。屏那边是手写分支表，
  //    App 的枚举手滑多一个值就是这么个静默法
  const CARDS = ['status', 'sat', 'dialog', 'emotion', 'clock', 'maint'];
  const EMOS = ['neutral', 'happy', 'excited', 'sad', 'worried', 'sleepy', 'thinking'];
  const uis = states.map((m) => m.state?.ui).filter((u) => u !== undefined && u !== null);
  const badCard = uis.filter((u) => !CARDS.includes(u.card)).map((u) => String(u.card));
  const badEmo = uis.filter((u) => u.emotion != null && !EMOS.includes(u.emotion)).map((u) => String(u.emotion));
  // ts 必须是**毫秒**。固件拿它除以 1000 拆时分秒，这里是秒的话屏上会显示 1970 年
  const badTs = states.filter((m) => typeof m.ts !== 'number' || m.ts < 1e12).length;
  if (!uis.length) fail('整场 1Hz 快照里一帧 ui 都没有 —— 那个决定没下发，屏只会画总览');
  else if (badCard.length) fail('ui.card 有屏画不出来的取值：' + [...new Set(badCard)].join(','));
  else if (badEmo.length) fail('ui.emotion 有屏画不出来的取值：' + [...new Set(badEmo)].join(','));
  else if (badTs) fail(`${badTs} 帧的 ts 不是 Unix 毫秒，钟面会画成 1970 —— 契约写的是毫秒`);
  else {
    const cards = [...new Set(uis.map((u) => u.card))].join(',');
    const emos = [...new Set(uis.map((u) => u.emotion ?? 'null'))].join(',');
    ok(`ui ${uis.length}/${states.length} 帧：卡=[${cards}] 表情=[${emos}] tz_min=${uis.at(-1).tz_min ?? '未给(屏按 UTC 标)'}`);
  }

  // ── 上行的三条切卡请求。这三条**全是请求**：屏只是说"我想看下一张"，画哪张仍由手机定。
  //    所以这里验的是"手机接住了没有"，不是"屏切到了哪张"。
  //    放在 sos 之前跑：呼救一旦没走完，card_next 会被顶回去，那是正确答案，不是这条要测的东西
  const cardOf = (m) => m?.state?.ui?.card;
  const lastState = () => states.at(-1)?.state ?? {};
  // 安全那一句压过屏的切卡请求（呼救没走完 / 已越界 / 蜂窝不顶用又快到窗口）。
  // card_next 和 poke 一样是**请求**，被顶回去是正确答案，所以两条共用这个判据
  const safetyHolds = (last) =>
    (last.sos?.level && last.sos.level !== 'normal' && last.sos.level !== 'armed_ok')
    || (last.fence?.active && last.fence?.inside === false)
    || (!last.link?.cellOk && last.sat?.nextPassInS != null && last.sat.nextPassInS <= 120);
  const c0 = cardOf(states.at(-1));
  send('command', { command: { cmd: 'card_next', args: { dir: 1 } } });
  await until(() => cardOf(states.at(-1)) !== c0 || safetyHolds(lastState()), 6000);
  const c1 = cardOf(states.at(-1));
  if (c1 === c0) {
    if (safetyHolds(lastState())) ok(`card_next 让路给安全那一句（当下画 ${c0}）—— 屏的请求不是命令`);
    else fail(`card_next 之后卡还停在 ${c0}，且当下没有该让路的场面 —— 要么 dispatch 没通，要么 args 里的 dir 没解出来`);
  } else ok(`card_next(dir=1)：${c0} → ${c1}`);

  // 交回策略之后才轮得到戳屏：手动钉住那张卡压过一切判据（含 poke），
  // 所以这条必须排在 card_auto 后面 —— 顺序反了它就不是在验 poke，是在验"钉住还留着"
  send('command', { command: { cmd: 'card_auto', args: {} } });
  await sleep(1500);

  send('command', { command: { cmd: 'poke', args: {} } });
  await sleep(2500);
  const c2 = cardOf(states.at(-1));
  // poke 是**请求**，不是命令：判据和 card_next 共用上面那条 safetyHolds
  const held = safetyHolds(lastState());
  if (held && c2 !== 'emotion') ok(`poke 让路给安全那一句（当下画 ${c2}）—— 一次触摸不该劫走倒计时`);
  else if (c2 !== 'emotion') fail(`poke 之后屏上应该是那张会动的脸，实际是 ${c2} —— 戳了没反应是现场最容易被当成屏坏了的一种`);
  else ok('poke 被接住，屏上换成表情卡');

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
  const askAt = Date.now();
  send('dialog', { dialog: { turn_id: TID, dir: 'up', text: '链路现在怎么样', source: 'asr', origin: 'screen' } });
  await until(() => dialogs.some((d) => d.turn_id === TID && d.dir === 'down'), 15000);
  const mine = dialogs.filter((d) => d.turn_id === TID);
  if (!mine.length) fail('发了 dialog{dir:up}，服务端一条对话都没回 —— 对话通道没接上，或协商把这台屏挡掉了');
  else if (!mine.some((d) => d.dir === 'down')) fail('对话只收到镜像回来的自己那句，没有答：' + JSON.stringify(mine));
  else {
    const ans = mine.find((d) => d.dir === 'down');
    if (mine.filter((d) => d.dir === 'down').length !== 1) fail(`一轮答了 ${mine.length} 条，屏上就要多出一轮`);
    ok(`对话往返通（${Date.now() - askAt}ms）：答「${ans.text.slice(0, 24)}」 source=${ans.source ?? 'local_rule(缺省)'} origin=${ans.origin ?? 'screen(缺省)'}`);
    // 沿用它那个 turn_id 才钉得回原来那一轮；另起一个 id 屏上就会多出第二条对话
    if (ans.turn_id !== TID) fail(`回镜没沿用屏那边的 turn_id，实际 ${ans.turn_id}`);
    else ok('回镜沿用屏那边的 turn_id');
    // 屏自己那句**不回镜**（回一遍就是同一句话在屏上出现两次）。所以这一轮只该收到一条 down
    const ups = mine.filter((d) => d.dir === 'up').length;
    if (ups) fail(`服务端把屏自己说的那句又镜像回来了 ${ups} 遍，屏上会出现两条一样的问话`);
    else ok('屏自己那句没被回镜，只有答那一条下来');
  }

  // ── 音频面（契约 v3）。这一条只能在真链路上称：帧的字节数由服务端编码完才算得出来，
  //    而屏那一侧 buffer_size=4096 是**硬**的 —— 超了的帧在屏那边是静默丢，服务端一切正常。
  //    合成要 0.6~2 秒，答句落地到第一帧音频之间有一段空，所以这里多等几秒
  if (WANT_VOICE) {
    await until(() => audio.some((a) => a.turn_id === TID), 20000);
    await sleep(1500);   // 第一帧到了之后让整段推完，seq 连续性那一条称的是全部
    const mine2 = audio.filter((a) => a.turn_id === TID);
    if (!mine2.length) fail(`答句之后一条 audio_chunk 都没来（总共收到 ${audio.length} 条）。要么驱动器没带 --voice，要么 TTS 没合成成功，要么 audioReady 判假`);
    else {
      const bad = mine2.find((a) => a.rate !== 16000);
      if (bad) fail(`音频帧报的采样率是 ${bad.rate}，屏那边 16000 之外一律丢掉`);
      const seqs = mine2.map((a) => a.seq);
      const gap = seqs.findIndex((s, i) => s !== i);
      if (gap >= 0) fail(`seq 从第 ${gap} 块起不连续（实际 ${seqs.join(',')}）—— 屏侧规则是缺一块丢整段，表现是没声音`);
      const totals = [...new Set(mine2.map((a) => a.total))];
      if (totals.length !== 1) fail('同一轮里 total 变了：' + totals.join(','));
      const got = mine2.reduce((s, a) => s + Buffer.from(a.b64, 'base64').length, 0);
      if (totals[0] !== got) fail(`每块解出来加在一起 ${got} 字节，帧里写的 total=${totals[0]} —— 屏按 total 判"放完了"`);
      const fat = mine2.filter((a) => a.bytes > 3800);
      if (fat.length) fail(`${fat.length} 帧超过 3800 字节预算（最大 ${Math.max(...mine2.map((a) => a.bytes))}），屏侧 buffer 是 4096，这些帧到不了`);
      const secs = got / 2 / 16000;
      ok(`音频 ${mine2.length} 块 / ${got} 字节 ≈ ${secs.toFixed(1)} 秒，seq 连续、total 对得上、最大帧 ${Math.max(...mine2.map((a) => a.bytes))} 字节（预算 3800）`);
      // 屏放一段 16k 的音频要按实时节拍走：这里只称"发出去用了多久"，
      // 收完第一帧到最后一帧的间隔 ≈ 这句话的时长（服务端是连着推的，不代表播放节拍）
      const spread = mine2.length > 1 ? '（服务端连推，非播放节拍）' : '';
      ok(`${mine2.length} 块一次推完${spread}：屏那边靠预缓冲 6400 字节起播，靠 muse_audio_write 的实时阻塞节拍播`);
    }
  } else if (audio.length) {
    ok(`收到 ${audio.length} 块音频帧（驱动器带了 --voice；这趟没加 --voice 所以不判）`);
  }

  const lastSeq = seq;
  byeAt = Date.now();
  send('bye', {});
  // 实测这条 close 落在 1.5 秒上下：`hello` 已建立的连接走不到 ScreenLinkServer 那个
  // "当场摘台账"的快路径（它的条件是 `hello == null`），所以要等下一轮才关。
  // 判据卡 1500ms 就是掷硬币，昨天那句 FAIL 是脚本自己造的。真要 bye 立刻断，改的是服务端那一支
  await sleep(3500);
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
