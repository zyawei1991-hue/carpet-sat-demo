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
 *
 * 屏那边 Kconfig 填 ws://<这台电脑的局域网IP>:8443。
 * Windows 记得放行入站（换了端口就把 localport 一起改掉）：
 *   netsh advfirewall firewall add rule name="screen-link-8443" dir=in action=allow protocol=TCP localport=8443
 */
import net from 'node:net';
import crypto from 'node:crypto';
import fs from 'node:fs';

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

/* ---------------- 一条连接 ---------------- */

let seq = 0;
const sessions = new Set();

const server = net.createServer(sock => {
  sock.setNoDelay(true);
  let ws = null;
  let acc = Buffer.alloc(0);
  let frag = null;

  const send = obj => { if (ws && !ws.destroyed) ws.write(encodeText(JSON.stringify(obj))); };

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
      send({ v: 2, t: 'ack', seq: ++seq, ack: { for_seq: m.seq } });
      return;
    }
    if (m.t === 'bye') { log('客户端道别（bye），台账当场标离线 —— 这条是对的'); cleanup(); return; }
    if (m.t === 'ack') { log(`ack for_seq=${m.ack?.for_seq}${m.ack?.error ? ' error=' + m.ack.error : ''}`); return; }
    if (m.t === 'dialog') {
      const d = m.dialog || {};
      log(`dialog↑ [${d.source}/${d.origin}] ${d.text}`);
      // 答一句同 turn_id 的 down。契约：dir:down 只补记录，不再触发答题
      send({
        v: 2, t: 'dialog', seq: ++seq, ts: Math.floor(Date.now() / 1000),
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
      send({ v: 2, t: 'ack', seq: ++seq, ack: { for_seq: m.seq, for_event_id: m.event?.event_id } });
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
