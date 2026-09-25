"""生成 Cheatwazi 内置音效：五声音阶触点音 / 淘汰下滑音 / 揭晓琶音。
柔和正弦+少量泛音，22.05kHz mono 16bit，总体积约 120KB。"""
import math
import struct
import wave

SR = 22050


def tone(freq, dur, tau=0.08, harmonics=((1, 1.0), (2, 0.3), (3, 0.12))):
    n = int(SR * dur)
    out = []
    for i in range(n):
        t = i / SR
        env = min(1.0, t / 0.005) * math.exp(-t / tau)
        s = sum(a * math.sin(2 * math.pi * freq * k * t) for k, a in harmonics)
        out.append(env * s)
    return out


def save(name, samples):
    peak = max(abs(s) for s in samples) or 1.0
    scale = 0.72 / peak
    with wave.open(name, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(s * scale * 32767)) for s in samples))


# 触点音：C5 D5 E5 G5 A5（五声音阶，按下序号取模映射）
for name, f in [
    ("pop_c5", 523.25),
    ("pop_d5", 587.33),
    ("pop_e5", 659.25),
    ("pop_g5", 783.99),
    ("pop_a5", 880.00),
]:
    save(name + ".wav", tone(f, 0.28))

# 淘汰音：短促下滑 420→100Hz
n = int(SR * 0.18)
out = []
for i in range(n):
    t = i / SR
    f = 420.0 - 320.0 * (t / 0.18)
    env = min(1.0, t / 0.004) * math.exp(-t / 0.05)
    out.append(env * math.sin(2 * math.pi * f * t))
save("elim.wav", out)

# 揭晓音：C5-E5-G5-C6 琶音，长尾音
seg = 0.09
out = [0.0] * int(SR * 0.85)
for idx, f in enumerate([523.25, 659.25, 783.99, 1046.50]):
    t0 = idx * seg
    s = tone(f, 0.85 - t0, tau=0.25)
    base = int(t0 * SR)
    for j, v in enumerate(s):
        if base + j < len(out):
            out[base + j] += v
save("win.wav", out)

print("done")
