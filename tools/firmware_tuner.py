#!/usr/bin/env python3
"""
Firmware-Tuner für VMAX-/BT638-Scooter
-------------------------------------

*   Liest ein OTA-ZIP oder BIN ein
*   Zeigt aktuelle Motor-Parameter (SpeedCut, MaxSpeed …)
*   Patched SpeedCut auf gewünschten km/h-Wert
*   Berechnet ggf. CRC32 neu
*   Baut optional ein Nordic-DFU-ZIP (nrfutil erforderlich)

Aufrufbeispiele
---------------

# Parameter nur anzeigen
python firmware_tuner.py update.zip --show

# SpeedCut auf 27 km/h setzen
python firmware_tuner.py update.zip --speed 27.0 --dfu
"""

import argparse
import binascii
import json
import os
import shutil
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

# ---------- Hilfsfunktionen -------------------------------------------------

def crc32(data: bytes) -> int:
    """CRC-32 (polynom 0xEDB88320)"""
    return binascii.crc32(data) & 0xFFFFFFFF


def find_param_blob(data: bytes) -> tuple[int, int] | None:
    """
    Sucht nach einem JSON-Blob, der die Felder "speedCut" UND "maxSpeed" enthält.
    Gibt (offset, length) zurück.
    """
    marker = b'"speedCut"'
    start = data.find(marker)
    if start == -1:
        return None
    end = data.find(b'}', start)
    if end == -1:
        return None
    level = 0
    for i in range(start, len(data)):
        ch = chr(data[i])
        if ch == '{':
            level += 1
        elif ch == '}':
            level -= 1
            if level == 0:
                end = i + 1
                break
    try:
        json.loads(data[start:end].decode("utf-8", "ignore"))
        return start, end - start
    except Exception:
        return None


def patch_speed(blob: bytes, speed_kmh: float) -> bytes:
    """Setzt speedCut im JSON-Blob auf neuen Wert (km/h)."""
    obj = json.loads(blob.decode())
    obj["speedCut"] = speed_kmh
    return json.dumps(obj, separators=(",", ":")).encode()


# ---------- Kernlogik -------------------------------------------------------

def process_file(path: Path, speed: float | None, make_dfu: bool):
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as zf:
            candidates = [n for n in zf.namelist() if n.endswith(".bin")]
            if not candidates:
                print("❌ Kein *.bin* in ZIP gefunden.")
                return
            name = candidates[0]
            data = zf.read(name)
    else:
        data = path.read_bytes()

    loc = find_param_blob(data)
    if not loc:
        print("❌ Kein Parameter-Blob gefunden.")
        return
    off, length = loc
    blob = data[off : off + length]
    params = json.loads(blob.decode())

    print("Aktuelle Parameter:")
    for k, v in params.items():
        print(f"  {k:12}: {v}")

    if speed is None:
        return

    print(f"\n⚙️  Patche speedCut → {speed} km/h …")
    patched_blob = patch_speed(blob, speed)

    crc_off = off + length
    new_data = bytearray(data)
    new_data[off : off + length] = patched_blob
    if crc_off + 4 <= len(data):
        old_crc = struct.unpack("<I", data[crc_off : crc_off + 4])[0]
        calc_crc = crc32(patched_blob)
        if old_crc in (calc_crc, crc32(blob)):
            struct.pack_into("<I", new_data, crc_off, calc_crc)
            print(f"CRC32 angepasst: 0x{old_crc:08X} → 0x{calc_crc:08X}")

    out_bin = path.with_name(f"{path.stem}_speed{speed}.bin")
    out_bin.write_bytes(new_data)
    print(f"✅ Geschrieben: {out_bin}")

    if make_dfu:
        if shutil.which("nrfutil") is None:
            print("⚠️  nrfutil nicht installiert – DFU-ZIP übersprungen.")
            return
        out_zip = out_bin.with_suffix(".zip")
        with tempfile.TemporaryDirectory() as tmp:
            tmp_bin = Path(tmp) / "app.bin"
            tmp_bin.write_bytes(new_data)
            cmd = [
                "nrfutil",
                "pkg",
                "generate",
                "--hw-version",
                "52",
                "--application",
                str(tmp_bin),
                "--application-version",
                "2",
                "--sd-req",
                "0x00",
                str(out_zip),
            ]
            subprocess.run(cmd, check=True)
        print(f"📦 DFU-ZIP erzeugt: {out_zip}")


# ---------- CLI -------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(prog="firmware_tuner.py")
    ap.add_argument("file", type=Path, help="OTA-ZIP oder BIN")
    ap.add_argument("--show", action="store_true", help="nur Parameter anzeigen")
    ap.add_argument("--speed", type=float, help="neuer speedCut-Wert (km/h)")
    ap.add_argument("--dfu", action="store_true", help="nrfutil-DFU-ZIP erzeugen")
    args = ap.parse_args()

    if args.show and args.speed:
        ap.error("--show und --speed schließen sich aus")
    if not args.show and args.speed is None:
        ap.error("Entweder --show ODER --speed <km/h> angeben")

    process_file(args.file, None if args.show else args.speed, args.dfu)


if __name__ == "__main__":
    main()
