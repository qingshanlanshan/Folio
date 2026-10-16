import numpy as np, struct, sys
b = open(sys.argv[1], "rb").read(); i = 0; tr = {0: [], 1: []}
while i + 5 <= len(b):
    t = b[i]; n = struct.unpack("<I", b[i + 1:i + 5])[0]
    tr[t].append(np.frombuffer(b[i + 5:i + 5 + 4 * n], np.float32)); i += 5 + 4 * n
for k, v in tr.items():
    if v:
        a = np.concatenate(v); print(k, round(len(a) / 16000, 2), "s, peak", float(np.abs(a).max()), "rms", float(np.sqrt((a ** 2).mean())))
