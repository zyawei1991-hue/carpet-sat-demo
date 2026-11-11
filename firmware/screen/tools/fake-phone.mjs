#!/usr/bin/env node
/*
 * 假手机服务端：不装 JDK、不编译 App、也不要真手机，先把固件那侧的三条判据验掉。
 *
 * 为什么要有它：真屏一上来就连真 App，出问题分不清是固件的还是 App 的。
 * 这个文件只用 Node 自带的模块（net/crypto），不装任何 npm 包。
 *
 * 用法（Windows / macOS / Linux 一样）：
 *   node fake-phone.mjs                      默认监听 0.0.0.0:8443，白名单里是 WS175B-A3F7
 *   node fake-phone.mjs --port 9100          换个端口（8443 被占用时用，见下面关于监听失败那句）
 *   node fake-phone.mjs --id WS175B-A3F7 --id desk-01      放行两块屏
 *   node fake-phone.mjs --reject             故意不开白名单，验固件收到 "demo lock" 会不会退避
 *   node fake-phone.mjs --drop 20            启动 20 秒后停推 state，验"3 秒变灰 / 10 秒重连"
 *   node fake-phone.mjs --state ./my.json    用一份自定义 state 当模板
 *   node fake-phone.mjs --v4-ready           演"App 已经把 mic_chunk 做完了"那一版：整帧 v=4、真拼段落 WAV
 *   node fake-phone.mjs --mic-close          即使 --v4-ready 也收到 mic_chunk 就关链（专称屏侧熔断）
 *   node fake-phone.mjs --no-v4              ack 只申报 [3]，验屏那边 armed=0、一条上行都不发
 *   node fake-phone.mjs --micdir ./out       上行 WAV 落哪儿（默认脚本旁边的 mic-capture/）
 *
 * 【2026-11-11 晚：默认这一台 = 今天装机那版 App，上行是**关链**的，不是拼段的。】
 * 读 App 那棵树称到的事实（`core/.../runtime/DemoRuntime.kt:513` + `ScreenLink.kt:112`）：
 * 它已经在 ack.protocols 里报了 4，但 CommandType 里没有 mic_chunk，严格枚举收到这一帧
 * 的处理是当场 `close(1000,"bad contract")`。所以默认档就是 v=3 + protocols [3,4] + 收到上行就关。
 * 要称"App 补完之后"那条路得明说 `--v4-ready`（整帧 v 抬到 4 —— 屏那边现在认的就是这个数，
 * 光有 protocols 里那个 4 不算许可）。
 *
 * 上行音频（契约 v4）这一台是真会处理的：屏松手后把整段按 2560 B/块推过来，
 * 这里按 turn_id 拼块、查缺号、封 RIFF 落成 .wav，再回 dialog{dir:up}+{dir:down}。
 * 屏侧那一趟用 `>mictest 3` 就能自己走完，不用真麦克风、也不用真手机。
 *
 * 屏那边 Kconfig 填 ws://<这台电脑的局域网IP>:8443。
 * Windows 记得放行入站（换了端口就把 localport 一起改掉）：
 *   netsh advfirewall firewall add rule name="screen-link-8443" dir=in action=allow protocol=TCP localport=8443
 */
import net from 'node:net';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function argList(name) {
  const out = [];
  process.argv.forEach((a, i) => { if (a === `--${name}` && process.argv[i + 1]) out.push(process.argv[i + 1]); });
  return out;
}
const PORT = Number(argList('port')[0] || 8443);
const IDS = new Set(argList('id').length ? argList('id') : ['WS175B-A3F7']);
const REJECT = process.argv.includes('--reject');
const DROP_AT = (() => { const v = argList('drop')[0]; return v ? Number(v) : null; })();
const STATE_FILE = argList('state')[0];
/* v4 上行的三条人。为什么要做成开关而不是直接写死：现在装在手机上的那版 App
 * 在 ack 里申报了 4，可 CommandType 里没有 mic_chunk —— 它的入站解码是严格枚举 +
 * runCatching，收到这一帧的处理是**当场关连接**（protocol/screen-link.md「第一条要求
 * 不是音频，是让屏先知道对面是几版」）。所以这一台假手机要能同时演这三种人：
 *   默认            整帧 v=3、protocols:[3,4]、收到 mic_chunk 就 close —— **今天装机那版 App**
 *   --v4-ready      整帧 v=4、真拼段落 WAV 回 dialog —— App 补完 mic_chunk 之后（CONTRACT_VERSION 抬到 4）
 *   --no-v4         protocols 只给 [3] —— 更早那版，屏那边 armed 该是 0
 * --mic-close 再叠在 --v4-ready 上强行关链，专门称屏侧那三道闸里的**熔断**：
 * `>mic` 里 rejected 该从 0 变 1，而且第二次按不该再把链路搞断。 */
const NO_V4 = process.argv.includes('--no-v4');
const READY = process.argv.includes('--v4-ready');
const MIC_CLOSE = process.argv.includes('--mic-close');
/* 每一帧 envelope 的 `v`：装机这版的 ScreenCodec.CONTRACT_VERSION 还是 3，补完上行才是 4
 * —— 而屏那道闸现在认的就是这个数（光 protocols 里那个 4 不算许可）。 */
const ENV_V = READY ? 4 : 3;
/* 收到 mic_chunk 拼段还是关链：没准备好就等于关链，和真 App 一致。 */
const micKills = () => MIC_CLOSE || !READY;
const MIC_OUT = (() => { const v = argList('micdir')[0]; return v && path.isAbsolute(v) ? v : path.join(path.dirname(fileURLToPath(import.meta.url)), v || 'mic-capture'); })();

const t0 = Date.now();
const log = (...a) => console.log(`[+${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a);

// Windows 控制台默认 GBK，中文日志会糊成一堆方块。看着像工具坏了，其实是编码。
// 提示用 ASCII 写，所以它自己不会被糊掉。
if (process.platform === 'win32') {
  console.log('[!] log is Chinese; if it shows as garbage run:  chcp 65001');
}

/* ---------------- 契约侧：帧的组与解 ---------------- */

function encodeText(str) {
  const payload = Buffer.from(str, 'utf8');
  const n = payload.length;
  let head;
  if (n < 126) head = Buffer.from([0x81, n]);
  else if (n < 65536) { head = Buffer.alloc(4); head[0] = 0x81; head[1] = 126; head.writeUInt16BE(n, 2); }
  else { head = Buffer.alloc(10); head[0] = 0x81; head[1] = 127; head.writeBigUInt64BE(BigInt(n), 2); }
  // 服务端→客户端**不掩码**，掩码只有反方向才有
  return Buffer.concat([head, payload]);
}

function encodeClose(reason) {
  const body = Buffer.concat([Buffer.from([0x03, 0xe8]), Buffer.from(reason, 'utf8')]); // 1000 = 正常关闭
  const head = Buffer.from([0x88, body.length]);
  return Buffer.concat([head, body]);
}

/** 客户端帧都是掩码的；攒在 buf 里，能解几条解几条，剩下的等下一个 TCP 包 */
function decodeFrames(sock, buf, onFrame) {
  let off = 0;
  while (buf.length - off >= 2) {
    const b0 = buf[off], b1 = buf[off + 1];
    const fin = (b0 & 0x80) !== 0;
    const opcode = b0 & 0x0f;
    const masked = (b1 & 0x80) !== 0;
    let len = b1 & 0x7f;
    let p = off + 2;
    if (len === 126) {
      if (buf.length - p < 2) break;
      len = buf.readUInt16BE(p); p += 2;
    } else if (len === 127) {
      if (buf.length - p < 8) break;
      len = Number(buf.readBigUInt64BE(p)); p += 8;
    }
    let key = null;
    if (masked) {
      if (buf.length - p < 4) break;
      key = buf.subarray(p, p + 4); p += 4;
    }
    if (buf.length - p < len) break;                     // 半包：等下一批字节
    let payload = buf.subarray(p, p + len);
    if (masked) {
      const out = Buffer.alloc(len);
      for (let i = 0; i < len; i++) out[i] = payload[i] ^ key[i % 4];
      payload = out;
    }
    off = p + len;
    onFrame(fin, opcode, payload);
  }
  return buf.subarray(off);
}

/* ---------------- state 模板 ---------------- */

function baseState() {
  if (STATE_FILE) {
    try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); }
    catch (e) { log('读不到 --state，用内置模板：', e.message); }
  }
  return {
    link: { primary: 'cell', cell_ok: true, cell_dbm: -67, sat_ok: false },
    sat: { phase: 'idle', module_ok: true, queue_pending: 0, next_pass_in_s: 300, cnr: 0 },
    gnss: { lat: 30.2741, lon: 120.1551, source: 'phone', fix_age_s: 1.2 },
    fence: { active: true, inside: true, name: '西湖景区', dist_m: 320 },
    sos: { level: 'normal', attempts: 0, sat_ack: false },
    sentinel: { armed: false },
    phone_battery: 63,
    demo_mode: true,
  };
}

/* ---------------- v4 上行：拼段 → WAV ---------------- */

/** PCM16/单声道 → 一个能直接双击听的 WAV。转写要的是这个容器不是裸流：
 *  之前称过裸 PCM 喂硅基流动会 500，封了 RIFF 才认（project-uplink-audio-v4）。 */
function wavBuffer(pcm, rate) {
  const n = pcm.length;
  const out = Buffer.alloc(44 + n);
  out.write('RIFF', 0); out.writeUInt32LE(36 + n, 4); out.write('WAVE', 8);
  out.write('fmt ', 12); out.writeUInt32LE(16, 16);
  out.writeUInt16LE(1, 20); out.writeUInt16LE(1, 22);          // PCM, 单声道
  out.writeUInt32LE(rate, 24); out.writeUInt32LE(rate * 2, 28); // 字节率
  out.writeUInt16LE(2, 32); out.writeUInt16LE(16, 34);          // 块对齐, 位深
  out.write('data', 36); out.writeUInt32LE(n, 40);
  pcm.copy(out, 44);
  return out;
}

/** 一条连接一段一段地攒；key = turn_id（屏在按下那一刻生成，手机不许换号） */
function makeMicAssembler() {
  const segs = new Map();
  return function micChunk(args) {
    const turn = String(args.turn_id ?? '');
    const sq = Number(args.seq);
    const total = Number(args.total);
    const rate = Number(args.rate) || 16000;
    if (!turn || !Number.isFinite(sq) || !Number.isFinite(total)) {
      log('mic_chunk 少了 turn_id/seq/total，这一帧没法拼：', JSON.stringify(args).slice(0, 160));
      return null;
    }
    let seg = segs.get(turn);
    if (!seg) {
      seg = { blocks: new Map(), total, rate, t0: Date.now(), bytes: 0 };
      segs.set(turn, seg);
      log(`上行段开：${turn} · total=${total} B (${(total / rate / 2).toFixed(2)} s @${rate})`);
    }
    let pcm;
    try { pcm = Buffer.from(String(args.b64 ?? ''), 'base64'); }
    catch (e) { log('b64 解不开：', e.message); return null; }
    if (seg.blocks.has(sq)) log(`重复 seq=${sq}，丢掉`);
    else { seg.blocks.set(sq, pcm); seg.bytes += pcm.length; }
    if (seg.bytes < seg.total) return null;                    // 还没齐，等下一块
    const ids = [...seg.blocks.keys()].sort((a, b) => a - b);
    const gaps = [];
    for (let i = 0; i < ids.length; i++) if (ids[i] !== i) gaps.push(i);
    const ordered = Buffer.concat(ids.map(i => seg.blocks.get(i)));
    const ms = Date.now() - seg.t0;
    let file = null;
    try {
      fs.mkdirSync(MIC_OUT, { recursive: true });
      file = path.join(MIC_OUT, `${turn.replace(/[^\w.-]/g, '_')}.wav`);
      fs.writeFileSync(file, wavBuffer(ordered, seg.rate));
    } catch (e) { log('WAV 落不下去：', e.message); }
    segs.delete(turn);
    log(`上行段齐：${turn} · ${ids.length} 块 ${ordered.length}/${seg.total} B ` +
        `${(ordered.length / seg.rate / 2).toFixed(2)} s · 收完用了 ${ms} ms · ` +
        `乱序缺号=${gaps.length ? gaps.join(',') : '无'} · ${file ?? '(没落成文件)'}`);
    return { turn, seconds: ordered.length / seg.rate / 2, bytes: ordered.length, ms };
  };
}

/* ---------------- 一条连接 ---------------- */

let seq = 0;
const sessions = new Set();

const server = net.createServer(sock => {
  sock.setNoDelay(true);
  let ws = null;
  let acc = Buffer.alloc(0);
  let frag = null;

  const send = obj => { if (ws && !ws.destroyed) ws.write(encodeText(JSON.stringify(obj))); };
  const mic = makeMicAssembler();

  sock.on('data', chunk => {
    acc = Buffer.concat([acc, chunk]);

    if (!ws) {
      const head = acc.toString('latin1');
      const eoh = head.indexOf('\r\n\r\n');
      if (eoh < 0) return;
      const key = /sec-websocket-key:\s*(\S+)/i.exec(head);
      if (!key) { sock.end('HTTP/1.1 400 Bad Request\r\n\r\n'); sock.destroy(); return; }
      const accept = crypto.createHash('sha1').update(key[1] + GUID).digest('base64');
      sock.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
      );
      ws = sock;
      acc = acc.subarray(eoh + 4);
      log('TCP + WebSocket 握手完成');
      if (REJECT) {
        log('--reject：直接 close("demo lock")，看固件会不会退避重连');
        ws.write(encodeClose('demo lock'));
        ws.end();
        return;
      }
    }

    acc = decodeFrames(sock, acc, (fin, opcode, payload) => {
      if (opcode === 0x8) { log('客户端 close'); cleanup(); return; }
      if (opcode === 0x9) { ws.write(Buffer.concat([Buffer.from([0x8a, payload.length]), payload])); return; }
      if (opcode === 0xa) return;                                     // pong，不用管
      if (opcode === 0x1 || opcode === 0x2 || opcode === 0x0) {
        const text = payload.toString('utf8');
        if (!fin) { frag = (frag || '') + text; return; }
        handle(frag ? frag + text : text);
        frag = null;
      }
    });
  });

  function handle(raw) {
    let m;
    try { m = JSON.parse(raw); } catch (e) {
      log('帧解不开，按契约 close("bad contract")：', raw.slice(0, 120));
      ws.write(encodeClose('bad contract'));
      ws.end();
      return;
    }
    if (m.t === 'hello') {
      const id = m.hello?.screen_id;
      if (!IDS.has(id)) {
        log(`白名单里没有 ${id} —— close("unknown screen")。这台屏的 id 要加进白名单：--id ${id}`);
        ws.write(encodeClose('unknown screen'));
        ws.end();
        return;
      }
      const s = { id, protocols: m.hello?.protocols ?? [1], state: baseState(), dialogUp: 0 };
      sessions.add({ sock: ws, send, s });
      log(`hello 通过：${id} · 固件 ${m.hello?.fw ?? '?'} · 申报契约 ${s.protocols.join('/')}`);
      /* ack.protocols 这一格 + 整帧的 `v` 一起才是屏侧那道自动闸的读数：
       * 装机那版是 `v=3` 但 protocols 里已经有 4（申报发早了、解码还没写）——
       * 屏现在**两个都点头才开上行**，所以默认这一档 armed 该是 0；
       * 要演"补完了"就 --v4-ready（整帧抬到 v=4）。--no-v4 是更早那版，protocols 只给 [3]。 */
      const ack = { for_seq: m.seq };
      if (!NO_V4) ack.protocols = READY ? [4] : [3, 4];
      else ack.protocols = [3];
      log(NO_V4 ? 'ack：--no-v4，只申报 [3] —— 屏那边 armed 该是 0'
                : (READY ? `ack：--v4-ready，整帧 v=${ENV_V}、mic_chunk 落 WAV 到 ${MIC_OUT}`
                         : `ack：整帧 v=${ENV_V}、protocols [3,4] —— 今天装机那版 App 的样子：` +
                           `屏那边 armed 该是 0（那条 W 就是它给的理由），发上来了就 close`));
      send({ v: ENV_V, t: 'ack', seq: ++seq, ack });
      return;
    }
    if (m.t === 'command') {
      const c = m.command || {};
      if (c.cmd !== 'mic_chunk') {
        /* 其余 cmd 这台假手机本来就没实现，认下来回一条 ack 就够，别装死。
         * 唯独 mic_chunk 不能走这条路 —— 今天真 App 的严格枚举不认它是**关链**，
         * 所以那一种由 micKills() 演（默认档就是它）。 */
        log(`command↑ ${c.cmd} args=${JSON.stringify(c.args ?? {}).slice(0, 120)}`);
        send({ v: ENV_V, t: 'ack', seq: ++seq, ack: { for_seq: m.seq } });
        return;
      }
      if (micKills()) {
        log(`mic_chunk↑ —— ${MIC_CLOSE ? '--mic-close' : '这台没 --v4-ready'}：按今天装机 App 的样子 close("bad contract")，看屏侧熔断`);
        ws.write(encodeClose('bad contract'));
        ws.end();
        return;
      }
      const done = mic(c.args ?? {});
      if (!done) return;                                    // 段还没齐
      /* 齐了先按契约回写转写（dir:up 是**记录**，不许再触发手机答题），再答一句。
       * 真 App 这里换成 ASR 出来的那句 + LLM 答句；假手机没转写，只证明"号对得上、
       * 字节数对得上、屏那侧显示链路走通了"。 */
      send({
        v: ENV_V, t: 'dialog', seq: ++seq, ts: Math.floor(Date.now() / 1000),
        dialog: { turn_id: done.turn, dir: 'up',
                  text: `（假手机）收到 ${done.bytes} B / ${done.seconds.toFixed(2)} 秒`,
                  source: 'asr', origin: 'screen' },
      });
      send({
        v: ENV_V, t: 'dialog', seq: ++seq, ts: Math.floor(Date.now() / 1000),
        dialog: { turn_id: done.turn, dir: 'down',
                  text: '这句是上行走通了的证据。真 App 会先转写再答题。',
                  source: 'local_rule', origin: 'screen' },
      });
      return;
    }
    if (m.t === 'bye') { log('客户端道别（bye），台账当场标离线 —— 这条是对的'); cleanup(); return; }
    if (m.t === 'ack') { log(`ack for_seq=${m.ack?.for_seq}${m.ack?.error ? ' error=' + m.ack.error : ''}`); return; }
    if (m.t === 'dialog') {
      const d = m.dialog || {};
      log(`dialog↑ [${d.source}/${d.origin}] ${d.text}`);
      // 答一句同 turn_id 的 down。契约：dir:down 只补记录，不再触发答题
      send({
        v: ENV_V, t: 'dialog', seq: ++seq, ts: Math.floor(Date.now() / 1000),
        dialog: {
          turn_id: d.turn_id, dir: 'down',
          text: '（假手机）这句我收到了。真 App 会答"车在哪 / 下次过境 / 还剩几条"这些状态题。',
          source: 'local_rule', origin: 'screen',
        },
      });
      return;
    }
    if (m.t === 'event') {
      log(`event↑ ${m.event?.kind} id=${m.event?.event_id}`);
      send({ v: ENV_V, t: 'ack', seq: ++seq, ack: { for_seq: m.seq, for_event_id: m.event?.event_id } });
      return;
    }
    log('不认识的帧类型：', m.t);
  }

  function cleanup() {
    for (const e of sessions) if (e.sock === sock) sessions.delete(e);
    try { sock.destroy(); } catch (e) { /* 已经断了 */ }
  }
  sock.on('close', () => { log('TCP 断了'); cleanup(); });
  sock.on('error', e => log('socket 错：', e.message));
}).listen(PORT, '0.0.0.0', () => {
  log(`假手机在 :${PORT} 等屏。白名单：${[...IDS].join(', ')}`);
  log('屏那边 Kconfig 填 ws://<这台电脑的局域网IP>:' + PORT);
});

/**
 * 监听失败不要抛栈。Windows 上 EACCES 太常见了 —— Hyper-V / IIS 会把一段端口划走，
 * 8443 正好落在保留段里过（这台开发机就是这样），抛栈只会让人以为是脚本写坏了。
 */
server.on('error', e => {
  if (e.code === 'EACCES') {
    console.log(`:${PORT} 被系统留着不给普通进程用（Windows 常见，Hyper-V/IIS 会保留一段端口）。换端口：node fake-phone.mjs --port 9100`);
    console.log('想看清保留段：netsh interface ipv4 show excludedportrange protocol=tcp');
  } else if (e.code === 'EADDRINUSE') {
    console.log(`:${PORT} 已经有东西在听了 —— 是另一个假手机没退干净，还是真 App 在跑？换端口：node fake-phone.mjs --port 9100`);
  } else {
    console.log('监听失败：', e.code, e.message);
  }
  process.exit(1);
});

/* 1Hz 推 state。--drop N 到点就停，用来验固件那两条时间线 */
let dropped = false;
setInterval(() => {
  if (DROP_AT !== null && !dropped && (Date.now() - t0) / 1000 > DROP_AT) {
    dropped = true;
    log(`--drop ${DROP_AT}s：从现在起不再推 state。盯 3 秒变灰、10 秒重连这两条`);
  }
  if (dropped) return;
  sessions.forEach(({ send, s }) => {
    const sat = s.state.sat;
    if (typeof sat.next_pass_in_s === 'number') {
      sat.next_pass_in_s = sat.next_pass_in_s <= 0 ? 300 : sat.next_pass_in_s - 1;
      sat.phase = sat.next_pass_in_s < 60 ? 'locked' : 'searching';
    }
    send({ v: 1, t: 'state', seq: ++seq, ts: Date.now(), state: s.state });
  });
}, 1000);

process.on('SIGINT', () => { log('退出'); process.exit(0); });
