#!/usr/bin/env python3
"""Simple BLE Motor‑Tuning helper for BT638 / GPST controllers.

* scans for the scooter (default name "BT638")
* reads current motor‑tuning frame via 160C
* patches speedCut (ID 3) in a selected profile
* writes the new packet on 160D and verifies the echo

Requires:  `pip install bleak`

Usage:
  python ble_motor_tuner.py             # speedCut → 27 km/h, profile 1
  python ble_motor_tuner.py --speed 25  # different value
  python ble_motor_tuner.py --profile 2 # different profile index (1‑5)
  python ble_motor_tuner.py --name "MyScooter"   # custom BLE name
  python ble_motor_tuner.py --addr AA:BB:CC:DD:EE:FF  # skip scan
"""
import argparse
import asyncio
import struct
from typing import Optional

from bleak import BleakClient, BleakScanner

UUID_160C = "0000160C-1212-EFDE-1523-785FEABCD123"
UUID_160D = "0000160D-1212-EFDE-1523-785FEABCD123"


# ---------- Motor‑Tuning helpers -------------------------------------------

def split_profiles(frame: bytes):
    """Parses the FD … FE frame and returns list of bytearrays (profiles)."""
    if not frame or frame[0] != 0xFD or frame[-1] != 0xFE:
        return []
    segs, cur = [], bytearray()
    for b in frame[1:-1]:
        if b == 0xFD:
            if cur:
                segs.append(cur)
            cur = bytearray()
        else:
            cur.append(b)
    if cur:
        segs.append(cur)
    return segs


def patch_speed(profile: bytearray, speed_kmh: float):
    """Sets element with ID 3 (speedCut) to new km/h *10 raw value."""
    target = 3  # SpeedCut ID
    while len(profile) <= target:
        profile.append(0xFF)
    profile[target] = int(round(speed_kmh * 10))


# ---------- BLE logic ------------------------------------------------------


async def run(addr: Optional[str], name: str, speed: float, index: int):
    # 1) find device
    if not addr:
        print("🔎 Scanning …")
        devs = await BleakScanner.discover(timeout=6.0)
        for d in devs:
            if d.name == name or name.lower() in (d.name or "").lower():
                addr = d.address
                break
        if not addr:
            print("❌ Kein Gerät gefunden (Name)")
            return
    print(f"➡️  Verbinde zu {addr} …")
    async with BleakClient(addr) as cli:
        if not cli.is_connected:
            print("❌ Verbindung fehlgeschlagen")
            return
        # 2) read 160C
        raw = await cli.read_gatt_char(UUID_160C)
        profiles = split_profiles(raw)
        if not profiles or index < 1 or index > len(profiles):
            print("❌ Profil nicht gefunden / Motor‑Tuning nicht unterstützt")
            return
        prof = profiles[index - 1]
        old_val = prof[3] / 10 if len(prof) > 3 and prof[3] != 0xFF else None
        print(f"Profil {index}: speedCut bisher {old_val} km/h")
        # 3) patch
        patch_speed(prof, speed)
        # 4) build write packet (index‑1, then values)
        pkt = bytes([index - 1]) + bytes(prof)
        # 5) write 160D
        await cli.write_gatt_char(UUID_160D, pkt, response=True)
        # 6) read back to verify
        raw2 = await cli.read_gatt_char(UUID_160C)
        prof2 = split_profiles(raw2)[index - 1]
        ok = prof2[3] == int(round(speed * 10))
        if ok:
            print(f"✓ 160D write ok – Controller bestätigt speedCut={speed} km/h")
        else:
            print("⚠️  Write scheinbar fehlgeschlagen – Wert wurde nicht übernommen")


# ---------- CLI ------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser(description="Set speedCut via BLE (160C/160D).")
    ap.add_argument("--name", default="BT638", help="BLE name filter")
    ap.add_argument("--addr", help="MAC address (skip scan)")
    ap.add_argument("--speed", type=float, default=27.0, help="new speedCut km/h")
    ap.add_argument("--profile", type=int, default=1, help="profile index (1‑5)")
    args = ap.parse_args()
    asyncio.run(run(args.addr, args.name, args.speed, args.profile))


if __name__ == "__main__":
    main()
