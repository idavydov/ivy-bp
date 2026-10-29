#!/usr/bin/env python3
"""Local IVY/Transtek BLE capture client. Run with uv run --with bleak ivy.py."""
import argparse
import asyncio
from datetime import datetime, timedelta
import json
import math
import os
from pathlib import Path
import struct
import sys
import time

ADDRESS = os.environ.get("IVY_ADDRESS", "")
BASE = "-0000-1000-8000-00805f9b34fb"
COMMAND = "00008a81" + BASE
REPLY = "00008a82" + BASE
DATA = "00008a91" + BASE
SERIAL = "00002a25" + BASE
EPOCH = datetime(2010, 1, 1)
IVY_PASSWORD = bytes.fromhex("b728c564")

def clock_seconds():
    # Mirror DeviceWrist: subtract 40 calendar years, then add raw zone
    # offset and current DST. Calendar resolves the shifted date in local time.
    now = datetime.now().astimezone()
    local = now.replace(tzinfo=None)
    try:
        shifted = local.replace(year=local.year - 40)
    except ValueError:
        shifted = local.replace(year=local.year - 40, day=28)
    seconds = int(time.mktime(shifted.timetuple()))
    raw_offset = int(datetime.fromtimestamp(0.015).astimezone().utcoffset().total_seconds())
    dst_offset = (time.timezone - time.altzone) if time.localtime().tm_isdst > 0 else 0
    return seconds + raw_offset + dst_offset

def decode(packet):
    # Layout documented for related Transtek devices; verify against the IVY LCD.
    if len(packet) < 13 or packet[0] != 0x1e:
        raise ValueError("Unrecognized record layout; raw bytes preserved")
    sys_bp, dia, unknown, seconds, pulse = struct.unpack_from("<HHHIH", packet, 1)
    return {
        "systolic_mmhg": sys_bp, "diastolic_mmhg": dia, "pulse_bpm": pulse,
        "device_time": (EPOCH + timedelta(seconds=seconds)).isoformat(),
        "unknown_5": unknown, "device_time_raw": seconds,
        "extra_bytes": packet[13:].hex(), "raw": packet.hex(),
        "verified_against_display": False,
    }

class Journal:
    def __init__(self, directory):
        directory.mkdir(parents=True, exist_ok=True)
        os.chmod(directory, 0o700)
        self.path = directory / (datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".jsonl")
        self.file = self.path.open("x", encoding="utf-8")
        os.chmod(self.path, 0o600)

    def record(self, event, **fields):
        self.file.write(json.dumps({"time": datetime.now().astimezone().isoformat(),
                                    "event": event, **fields}) + "\n")
        self.file.flush()
        os.fsync(self.file.fileno())

    def close(self):
        self.file.close()

async def capture(args):
    from bleak import BleakClient, BleakScanner
    journal = Journal(Path(args.output))
    queue = asyncio.Queue()
    ended = asyncio.Event()
    records = []
    problem = None
    print(f"Capture file: {journal.path}", flush=True)
    print("Waiting for the monitor. " + ("Enter pairing mode now." if args.capture_password else "Wake with MEM when ready; no measurement needed."), flush=True)
    try:
        device = await BleakScanner.find_device_by_address(args.address, timeout=args.scan_timeout)
        if device is None:
            raise RuntimeError("Monitor not seen. No commands sent. Try again when ready.")
        journal.record("discovered", address=device.address, name=device.name)
        async with BleakClient(device, disconnected_callback=lambda _: ended.set(),
                               timeout=15) as client:
            required = (COMMAND, REPLY, DATA)
            if any(client.services.get_characteristic(u) is None for u in required):
                raise RuntimeError("Expected Transtek characteristics missing; no commands sent")
            services = [{"uuid": s.uuid, "characteristics": [
                {"uuid": c.uuid, "properties": c.properties, "handle": c.handle}
                for c in s.characteristics]} for s in client.services]
            journal.record("services", services=services)
            # APK DeviceWrist uses a fixed big-endian XOR key, not the MAC.
            password = bytes.fromhex(args.password) if args.password else IVY_PASSWORD
            if len(password) != 4:
                raise RuntimeError("Password must contain four bytes")

            def received(characteristic, value):
                # BLE acknowledges indications automatically. Persist raw bytes before
                # application commands; a downloaded record may be erased by the device.
                packet = bytes(value)
                try:
                    journal.record("rx", uuid=characteristic.uuid, hex=packet.hex())
                except Exception as exc:
                    queue.put_nowait(("journal_error", exc))
                    return
                queue.put_nowait((characteristic.uuid, packet))

            async def send(packet):
                journal.record("tx_attempt", uuid=COMMAND, hex=packet.hex())
                await asyncio.wait_for(client.write_gatt_char(COMMAND, packet, response=True), timeout=5)
                journal.record("tx_complete", hex=packet.hex())

            print("Connected. Discovering and listening for authentication.", flush=True)
            await client.start_notify(REPLY, received)
            authenticated = False
            deadline = asyncio.get_running_loop().time() + 90
            while not ended.is_set():
                if asyncio.get_running_loop().time() >= deadline:
                    raise RuntimeError("Session time limit reached")
                try:
                    uuid, packet = await asyncio.wait_for(queue.get(), timeout=2)
                except TimeoutError:
                    if records:
                        journal.record("data_idle")
                        break
                    continue
                if uuid == "journal_error":
                    raise RuntimeError("Cannot persist incoming data; stopping") from packet
                if uuid == REPLY:
                    if len(packet) == 5 and packet[0] == 0xa0:
                        journal.record("pairing_password", password_hex=packet[1:].hex())
                        print("Monitor password captured and saved in the private journal.", flush=True)
                        if args.capture_password:
                            journal.record("password_capture_complete")
                            return 0
                        raise RuntimeError("Pairing password captured; restart in normal measurement mode with --password")
                    if len(packet) == 5 and packet[0] == 0xa1:
                        if args.capture_password:
                            raise RuntimeError("Normal-mode challenge received. Use pairing mode to capture the password; no response sent")
                        if authenticated:
                            raise RuntimeError("Repeated authentication challenge; stopping")
                        response = bytes(a ^ b for a, b in zip(packet[1:], password))
                        await send(b"\x20" + response)
                        seconds = clock_seconds()
                        await send(b"\x02" + struct.pack("<I", seconds))
                        authenticated = True
                        await client.start_notify(DATA, received)
                    elif packet == b"\x22":
                        journal.record("device_finished")
                        break
                    else:
                        journal.record("unhandled_command", hex=packet.hex())
                elif uuid == DATA:
                    try:
                        reading = decode(packet)
                    except ValueError as exc:
                        journal.record("unknown_record", reason=str(exc), hex=packet.hex())
                        raise RuntimeError("Unknown IVY record layout; raw packet saved, no application ACK sent") from exc
                    journal.record("reading", **reading)
                    records.append(reading)
                    # The APK does not ACK each history record with 0x22.
                    # Let records stream; disconnect after the stream goes idle.
            # Drain queued packets to preserve any final decodable readings.
            while not queue.empty():
                uuid, packet = queue.get_nowait()
                if uuid == DATA:
                    try:
                        reading = decode(packet)
                        journal.record("reading", **reading)
                        records.append(reading)
                    except ValueError:
                        pass
            journal.record("session_end", readings=len(records))
    except Exception as exc:
        problem = str(exc)
        journal.record("error", message=problem)
    finally:
        journal.close()
    for reading in records:
        print(json.dumps(reading))
    print(f"Saved {len(records)} reading(s) in {journal.path}", flush=True)
    if problem:
        print(f"Stopped: {problem}", file=sys.stderr)
        return 1
    if not records:
        print("No readings received; authentication/download is not yet verified.", file=sys.stderr)
        return 1
    return 0

def self_test():
    packet = b"\x1e" + struct.pack("<HHHIH", 123, 78, 0, 86400, 65) + bytes([1, 4, 0, 1])
    result = decode(packet)
    assert result["systolic_mmhg"] == 123 and result["diastolic_mmhg"] == 78
    assert result["pulse_bpm"] == 65 and result["device_time"] == "2010-01-02T00:00:00"
    for invalid in (b"", packet[:12], b"\x00" + packet[1:]):
        try:
            decode(invalid)
        except ValueError:
            continue
        raise AssertionError("Invalid record accepted")
    challenge = bytes.fromhex("90001b01")
    password = IVY_PASSWORD
    assert bytes(a ^ b for a, b in zip(challenge, password)).hex() == "2728de65"
    print("Offline checks passed: record decoding, malformed packets, authentication vector.")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", default=ADDRESS)
    parser.add_argument("--password", help="Four-byte password as eight hex characters")
    parser.add_argument("--capture-password", action="store_true", help="Listen only for the pairing password; send no protocol commands")
    parser.add_argument("--output", default="captures")
    parser.add_argument("--scan-timeout", type=float, default=120)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.address:
        parser.error("Set --address or IVY_ADDRESS to your monitor Bluetooth address")
    if args.password:
        try:
            if len(bytes.fromhex(args.password)) != 4:
                parser.error("--password needs four bytes")
        except ValueError:
            parser.error("--password needs hexadecimal bytes")
    if not math.isfinite(args.scan_timeout) or args.scan_timeout <= 0:
        parser.error("--scan-timeout must be positive and finite")
    try:
        return asyncio.run(capture(args))
    except KeyboardInterrupt:
        print("Interrupted. Previously journaled packets remain saved.", file=sys.stderr)
        return 130

if __name__ == "__main__":
    sys.exit(main())
