#!/usr/bin/env python3
"""Check the settings page's Chinese table (muse tree:
esp32/components/muse/carpet/carpet_lang_zh.c) before it is flashed.

Why a script and not eyes: a translation is looked up by the English the code
says, so rewording a row in muse_settings_ui.c silently drops it back to
English, and a hanzi outside the 16 px Noto subset draws a hollow box. Both
look like a finished page until someone reads it. This checks five things:

  1. every entry parsed, and no key repeats (a repeat is an unreachable row)
  2. every conversion (%s, %d, %%) the English has, its Chinese has too
  3. every character in a Chinese value is one the build has a glyph for
     (ASCII, LVGL's symbols in Montserrat, or the Noto 16 px subset)
  4. every key is still a string literal in the file that draws it
  5. how wide the Chinese is where it's drawn. The Noto face advances 16 px
     for every glyph (adv_w 256, in 1/16 px units), and a hanzi can't be
     squeezed, so this is the count that decides whether a label gets an
     ellipsis. Which budget applies comes from the call the English sits in:
     a row's name gets about 124 px once its icon and its value have taken
     theirs, a value gets 130, and a note wraps at 330 so it only costs lines.

Usage:  python check_zh_table.py [muse_esp32_dir]   (default D:/dev/projects/
        muse-gadget-sdk-main/esp32)
"""

import re
import sys

# A printf conversion. The space flag is deliberately not in the class: these
# strings are mostly plain labels, and "1% steps" is not a conversion of "s".
ESPEC = r'%[-+#0]*(?:\d+|\*)?(?:\.(?:\d+|\*))?(?:hh|h|ll|l|j|z|t|L)?[diouxXcsfeEgGpn%]'

# Where the settings page's English lives; a key that stopped matching means
# one of these files was reworded without the table. The state names come from
# muse_chat.c / muse_link.c, drawn as the values of the Muse row and page, and
# the second line of those status notes from muse_chat_link.c and the board's
# glue in main/muse_glue.c - all of them are keys, so all of them are read.
DRAWERS = ("components/muse/muse_settings_ui.c", "components/muse/muse_menu.c",
           "components/muse/muse_ui.c", "components/muse/muse_keypad.c",
           "components/muse/muse_chat.c", "components/muse/muse_chat_link.c",
           "components/muse/muse_link.c", "main/muse_glue.c")
TABLE = "components/muse/carpet/carpet_lang_zh.c"

# Keys budgets() can't place - no call name sits on the way to them, because
# they are returned by a muse_*_state_name(), stored in an option table, or
# copied through a buffer - with the budget they really get. Anything not
# listed here is judged against the tightest label on the page, so a new one
# can only ever make the check stricter, never looser; add an entry, with the
# reason, once you have read the source and found where it is drawn.
INDIRECT = {
    # tick_hatch() composes this into link[] as "Muse app: %s\n%s" and hands
    # it to s_link_status, which is a note(): 330 px a line, wrapping. The
    # Chinese is 363 px against the English 553, so it takes the same two
    # lines of the note the upstream page already spends.
    "paired with another SDK token, pair again to use this one": 330,
    # The second %s of a status note, written into wifi_status.detail /
    # hatch_status.detail by main/muse_glue.c and muse_chat_link.c and drawn
    # through muse_lang_menu() at muse_settings_ui.c's tick. A note wraps, so
    # these cost lines rather than losing words.
    "No saved network nearby": 330,
    "Can't join; retrying": 330,
    "Through Home Link, text replies": 330,
    "Connects when you talk": 330,
    "Waiting for Wi-Fi": 330,
    "Pair in the Muse app": 330,
}
NOTO = "components/muse/fonts/muse_font_cjk_noto_16.c"
SYMBOLS = "managed_components/lvgl__lvgl/src/font/lv_symbol_def.h"


def load(path, mode="r", **kw):
    with open(path, mode, **kw) as f:
        return f.read()


def symbols(root):
    text = open(root + "/" + SYMBOLS, encoding="utf-8", errors="replace").read()
    out = {}
    for name, val in re.findall(r'#define\s+(LV_SYMBOL_\w+)\s+"((?:\\x[0-9A-Fa-f]{2})+)"', text):
        out[name] = bytes(int(h, 16) for h in re.findall(r"\\x([0-9A-Fa-f]{2})", val))
    return out


def joined_literals(text):
    """Glue adjacent string literals ("a" "b", across lines) into "ab"."""
    prev = None
    while prev != text:
        prev = text
        text = re.sub(r'"[ \t]*\r?\n[ \t]*"', "", text)
    return text


# One pass over C source: a comment, a character constant, or a string literal.
# Swallowing the first two whole is what keeps the quoting honest - a stray
# double quote in a comment would pair with the next real one and shift every
# literal after it, which reads as "this key is nowhere in the code".
C_TOKEN = re.compile(r'/\*.*?\*/|//[^\n]*|\'(?:\\.|[^\'\\])\'|"(?:[^"\\]|\\.)*"', re.S)


def literals(text):
    """(contents, index) for every string literal, comments skipped."""
    for m in C_TOKEN.finditer(text):
        if m.group(0)[0] == '"':
            yield m.group(0)[1:-1], m.start()


def drawn_text(root, syms):
    """Every file that puts words on the screen, as one text: adjacent
    literals glued, and LV_SYMBOL_x turned into the character it draws so
    `LV_SYMBOL_REFRESH "  Scan for networks"` reads as the one string the code
    actually says - which is what the table's key has to match."""
    text = joined_literals("\n".join(load(root + "/" + p, encoding="utf-8", errors="replace")
                                     for p in DRAWERS))
    name2char = {n: v.decode("utf-8", "replace") for n, v in syms.items()}
    text = re.sub(r'(LV_SYMBOL_\w+)\s*"((?:[^"\\]|\\.)*)"',
                  lambda m: '"%s%s"' % (name2char.get(m.group(1), "?"), m.group(2)), text)
    return re.sub(r'LV_SYMBOL_\w+', lambda m: '"%s"' % name2char.get(m.group(0), "?"), text)


def montserrat16_advances(path):
    """Montserrat 16's advance per ASCII codepoint, in px. Its first cmap is a
    plain block from 32 for 95 characters, so glyph_dsc[1 + cp - 32] is its
    letter; LVGL stores the advance in sixteenths of a pixel."""
    text = load(path, encoding="utf-8", errors="replace")
    body = text.split("lv_font_fmt_txt_glyph_dsc_t glyph_dsc[] = {", 1)[1].split("\n};", 1)[0]
    adv = [int(a) for a in re.findall(r"\.adv_w = (\d+)", body)]
    return {32 + i: adv[1 + i] / 16.0 for i in range(95)}


def width(text, ascii_adv):
    """The px a 16 px label of this text takes: the CJK face is 16 px per
    glyph whatever the character, ASCII comes from Montserrat's own table, and
    an LVGL symbol is drawn in the square its icon font uses."""
    total = 0.0
    for ch in text:
        cp = ord(ch)
        if 0xE000 <= cp <= 0xF8FF:
            total += 16.0
        elif cp < 0x80:
            total += ascii_adv.get(cp, 16.0)
        else:
            total += 16.0
    return total


def budgets(code_text):
    """Where each English string is drawn, from the call it sits in.

    A row's name gets LIST_W 330 less its card's padding, its icon and its
    value label (capped at 130 px) - about 124 px, and LONG_MODE_DOTS cuts it
    there. A note is `lv_pct(100)` with LONG_MODE_WRAP, so it gets 330 px a
    line and pays in lines instead. A status line composed into a buffer with
    snprintf or strlcpy goes to one of those notes (s_wifi_status,
    s_ble_status, s_batt_status, s_about); the formats that reach set_val are
    the row values and get 130. An open_text hint is the keyboard field's
    placeholder, a little under 300 px.

    The call is the nearest name before the literal, searched back over the
    text rather than by line: these calls wrap, and the literal is often on a
    line of its own.
    """
    CALLS = {
        "row": 124, "switch_row": 226, "slider": 150, "info_row": 150,
        "button": 298, "note": 330, "page": 300, "set_val": 130, "set_text": 130,
        "label": 124, "open_text": 290, "strlcpy": 330, "snprintf": 330,
    }
    out = {}
    for lit, start in literals(code_text):
        key = lit.replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\").encode()
        back = code_text[max(0, start - 400):start]
        best, at = None, -1
        for name, budget in CALLS.items():
            i = back.rfind(name + "(")
            if i > at and ";" not in back[i:]:
                best, at = budget, i
        if best is not None:
            # set_text() and the composed buffers reach both a row's value
            # (130 px, DOTS) and a page's status note (330 px, wrapping); the
            # label's own name says which.
            dest = re.search(r"(?:set_text|snprintf|strlcpy)\(\s*(\w+)", back[at:])
            if dest and best == 130 and re.search(r"note|status|about|awake", dest.group(1)):
                best = 330
            out.setdefault(key, []).append(best)
    return out


def run(root):
    syms = symbols(root)
    src = load(root + "/" + TABLE, encoding="utf-8")
    body = src.split("static const entry_t TABLE[] = {", 1)[1].split("\n};", 1)[0]
    flat = joined_literals(body).encode("utf-8")

    pairs = re.findall(rb'\{\s*((?:LV_SYMBOL_\w+\s+)?"(?:[^"\\]|\\.)*"(?:"(?:[^"\\]|\\.)*")*)\s*,\s*'
                       rb'((?:LV_SYMBOL_\w+\s+)?"(?:[^"\\]|\\.)*"(?:"(?:[^"\\]|\\.)*")*)\s*,?\s*\}', flat)
    lines = [l for l in flat.decode("utf-8").splitlines() if l.strip().startswith("{")]
    problems = []
    if len(pairs) != len(lines):
        problems.append("parsed %d of %d entries - the regex missed rows, fix this script first"
                        % (len(pairs), len(lines)))
        print("\n".join("PROBLEM: " + p for p in problems))
        return 1

    def bytes_of(fragment):
        out = bytearray()
        for tok in re.findall(rb'LV_SYMBOL_\w+|"(?:[^"\\]|\\.)*"', fragment):
            if tok.startswith(b"LV_SYMBOL"):
                out += syms.get(tok.decode(), b"?UNKNOWN_SYMBOL?")
            else:
                out += tok[1:-1].replace(b'\\"', b'"').replace(b"\\n", b"\n").replace(b"\\\\", b"\\")
        return bytes(out)

    entries = [(bytes_of(k), bytes_of(v)) for k, v in pairs]
    noto = {int(c, 16) for c in re.findall(r"/\* U\+([0-9A-F]+) ",
                                           open(root + "/" + NOTO, encoding="utf-8",
                                                errors="replace").read())}
    keys = [k for k, _ in entries]
    for k in sorted(set(keys)):
        if keys.count(k) > 1:
            problems.append("duplicate key %r" % k)
    conv = re.compile(ESPEC.encode())
    for k, v in entries:
        tag = k.decode("utf-8", "replace")[:70]
        if b"UNKNOWN_SYMBOL" in k or b"UNKNOWN_SYMBOL" in v:
            problems.append("unresolved LV_SYMBOL in %r" % tag)
        if conv.findall(k) != conv.findall(v):
            problems.append("conversions %r vs %r in %r" % (conv.findall(k), conv.findall(v), tag))
        try:
            text = v.decode("utf-8")
        except UnicodeDecodeError:
            problems.append("value for %r is not valid UTF-8" % tag)
            continue
        for ch in text:
            cp = ord(ch)
            if cp < 0x80 or 0xE000 <= cp <= 0xF8FF or cp in noto:
                continue
            problems.append("no glyph U+%04X (%s) in the 16px Noto, value of %r" % (cp, ch, tag))
        if len(v) > 95:
            problems.append("value is %d bytes, past the 96-byte fold buffer, for %r" % (len(v), tag))

    ascii_adv = montserrat16_advances(root + "/managed_components/lvgl__lvgl/src/font/lv_font_montserrat_16.c")
    drawn = drawn_text(root, syms)
    place = budgets(drawn)
    SAMPLE = {"%s": "a" * 12, "%d": "100", "%.1f": "12.3", "%%": "%", "%ld": "100", "%02d": "12", "%.12s": "a" * 12}
    for k, v in entries:
        try:
            text = v.decode("utf-8")
        except UnicodeDecodeError:
            continue
        shown = re.sub(ESPEC, lambda m: SAMPLE.get(m.group(0), "0"), text)
        lines = [width(line, ascii_adv) for line in shown.split("\n")]
        cap = place.get(k)
        if cap is None:
            # Nothing in these files says the string where a call name can be
            # read off it: a state word returned by muse_*_state_name(), an
            # entry of an option table, a value reached through a variable.
            # Take the tightest budget any of them could land in - a row name,
            # 124 px with DOTS - so a long one is caught instead of excused.
            # INDIRECT names the few that a hand read of the source proves go
            # somewhere wider.
            cap = [INDIRECT.get(k.decode("utf-8", "replace"), 124)]
        cap = min(cap)
        if cap == 330:
            # A note wraps, so it never loses words - it only runs down the
            # page. Two lines of it is what a page has room for beside a list.
            px, cap = sum(lines), 660
        else:
            px = max(lines)   # DOTS labels cut the widest line with "..."
        if px > cap:
            problems.append("%r draws %d px where its label gets %d (key %r)"
                            % (text, px, cap, k.decode("utf-8", "replace")[:60]))

    code = b"".join(load(root + "/" + p, "rb") for p in DRAWERS)
    code = joined_literals(code.decode("utf-8", "replace")).encode("utf-8", "replace")
    code = re.sub(rb'(LV_SYMBOL_\w+)\s*"((?:[^"\\]|\\.)*)"',
                  lambda m: b'"' + syms.get(m.group(1).decode(), b"?") + m.group(2) + b'"', code)
    code = re.sub(rb"(LV_SYMBOL_\w+)",
                  lambda m: b'"' + syms.get(m.group(1).decode(), b"?") + b'"', code)
    said = set()
    for s, _ in literals(code.decode("utf-8", "replace")):
        said.add(s.replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\").encode())
    for k in sorted(set(keys)):
        if k not in said:
            problems.append("nothing in the code says %r any more" % k.decode("utf-8", "replace"))

    print("entries: %d   noto glyphs: %d" % (len(entries), len(noto)))
    for p in problems:
        print("PROBLEM:", p)
    print("problems:", len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(run(sys.argv[1] if len(sys.argv) > 1 else "D:/dev/projects/muse-gadget-sdk-main/esp32"))
