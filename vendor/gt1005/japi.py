import struct, zipfile, sys

TAGS = {7:2,9:4,10:4,11:4,8:2,4:4,3:4,5:8,6:8,12:4,17:4,18:4,16:2,15:3,19:2,20:2}

def parse_class(data):
    off = 8
    n = struct.unpack_from('>H', data, off)[0]; off += 2
    pool = {}; i = 1
    while i < n:
        t = data[off]; off += 1
        if t == 1:
            l = struct.unpack_from('>H', data, off)[0]; off += 2
            pool[i] = data[off:off+l].decode('utf-8', 'replace'); off += l
        elif t == 2:
            l = struct.unpack_from('>H', data, off)[0]; off += 2 + l
        else:
            sz = TAGS[t]
            if t in (7, 8, 16, 19, 20):
                pool[i] = ('REF', struct.unpack_from('>H', data, off)[0])
            off += sz
            if t in (5, 6): i += 1
        i += 1
    acc, this, sup, ic = struct.unpack_from('>HHHH', data, off); off += 8 + 2*ic
    cnt = struct.unpack_from('>H', data, off)[0]; off += 2
    fields = []
    for _ in range(cnt):
        a, ni, no, ac = struct.unpack_from('>HHHH', data, off); off += 8
        fields.append((pool.get(ni), pool.get(no), a))
        for _ in range(ac):
            ln = struct.unpack_from('>I', data, off+2)[0]; off += 6 + ln
    cnt = struct.unpack_from('>H', data, off)[0]; off += 2
    methods = []
    for _ in range(cnt):
        a, ni, no, ac = struct.unpack_from('>HHHH', data, off); off += 8
        methods.append((pool.get(ni), pool.get(no), a))
        for _ in range(ac):
            ln = struct.unpack_from('>I', data, off+2)[0]; off += 6 + ln
    return fields, methods

def cls_name(c): return c[:-6].replace('/', '.')

def dump(jar, patterns):
    z = zipfile.ZipFile(jar)
    for name in sorted(z.namelist()):
        if not name.endswith('.class') or '$' in name: continue
        cn = cls_name(name)
        if patterns and not any(p in cn for p in patterns): continue
        try:
            fields, methods = parse_class(z.read(name))
        except Exception as e:
            print('!! skip', cn, e); continue
        print('\n### ' + cn)
        for f, d, a in fields:
            print('   F %s : %s' % (f, d))
        for m, d, a in methods:
            if m.startswith('access$'): continue
            print('   M %s%s' % (m, d))

if __name__ == '__main__':
    dump(sys.argv[1], sys.argv[2:])
