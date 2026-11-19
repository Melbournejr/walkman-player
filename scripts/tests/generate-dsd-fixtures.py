from pathlib import Path
import struct

root = Path("fixtures")
root.mkdir(exist_ok=True)

for multiplier in (1, 2, 4):
    rate = multiplier * 2_822_400
    blocks = (rate // 8 + 4095) // 4096
    body = (bytes([0x69]) * 4096 + bytes([0x96]) * 4096) * blocks
    prefix = b"DSD " + struct.pack("<QQQ", 28, 92 + len(body), 0)
    fmt = b"fmt " + struct.pack("<QIIIIIIQII", 52, 1, 0, 2, 2, rate, 1, rate, 4096, 0)
    (root / f"{multiplier * 64}.dsf").write_bytes(prefix + fmt + b"data" + struct.pack("<Q", len(body) + 12) + body)


def chunk(tag, data):
    return tag + struct.pack(">Q", len(data)) + data + (b"\0" if len(data) % 2 else b"")


properties = (
    b"SND "
    + chunk(b"FS  ", struct.pack(">I", 2_822_400))
    + chunk(b"CHNL", b"\0\x02SLFTRGHT")
    + chunk(b"CMPR", b"DSD \x0enot compressed")
)
body = b"DSD " + chunk(b"FVER", b"\x01\x05\x00\x00") + chunk(b"PROP", properties)
body += chunk(b"DSD ", b"\x69\x96" * (2_822_400 // 8))
(root / "raw.dff").write_bytes(chunk(b"FRM8", body))
(root / "malformed.dsf").write_bytes(b"DSD " + b"\xff" * 16)
