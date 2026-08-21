#!/usr/bin/env python3
"""AES-256-CBC 加密工具：生成 UrlConfig 密文

key 拼装（与 cpp/security.cpp 一致）：
  token = "TVLiveSec!2026Sec"[:16]  (固定)
  key[0:16]  = KEY_PART_A XOR token
  key[16:32] = KEY_PART_B XOR token
"""
import base64
import os

# 与 cpp/security.cpp 中的 KEY_PART_A/B 一致
KEY_PART_A = bytes([0x9c, 0x3f, 0xa1, 0x77, 0x55, 0x88, 0x10, 0xcc,
                    0x2d, 0x4b, 0xe6, 0x91, 0x07, 0xb3, 0xd5, 0x42])
KEY_PART_B = bytes([0x7a, 0xb1, 0x05, 0xe9, 0x33, 0x6f, 0xc2, 0x4d,
                    0x18, 0xfa, 0x82, 0x59, 0xa0, 0x21, 0x6c, 0xd7])
TOKEN = b"TVLiveSec!2026Se"  # 16 bytes

key = bytes(a ^ t for a, t in zip(KEY_PART_A, TOKEN)) + \
      bytes(b ^ t for b, t in zip(KEY_PART_B, TOKEN))

print("AES key (hex):", key.hex())

# 尝试用 cryptography；没有就回退到 pycryptodome
try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.primitives import padding
    def encrypt(plain: bytes, key: bytes) -> bytes:
        iv = os.urandom(16)
        padder = padding.PKCS7(128).padder()
        padded = padder.update(plain) + padder.finalize()
        c = Cipher(algorithms.AES(key), modes.CBC(iv))
        enc = c.encryptor()
        return iv + enc.update(padded) + enc.finalize()
    backend = "cryptography"
except ImportError:
    try:
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import pad
        def encrypt(plain: bytes, key: bytes) -> bytes:
            iv = os.urandom(16)
            return iv + AES.new(key, AES.MODE_CBC, iv).encrypt(pad(plain, 16))
        backend = "pycryptodome"
    except ImportError:
        print("ERROR: 需要安装 cryptography 或 pycryptodome")
        print("  pip install cryptography")
        raise

URLS = {
    "LIVE_1": "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u",
    "LIVE_2": "https://gitee.com/qf_1111/iptv/raw/master/iptvedqu.m3u",
    "EPG_1":  "https://epg.catvod.com/epg.xml",
    "EPG_2":  "https://e.erw.cc/all.xml.gz",
}
print("using backend:", backend)
for name, url in URLS.items():
    c = encrypt(url.encode("utf-8"), key)
    b64 = base64.b64encode(c).decode("ascii")
    print(f'\nprivate static final String B_{name} = "{b64}";')
