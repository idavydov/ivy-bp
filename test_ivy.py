"""Offline transport checks for the handshake recovered from the APK."""
import argparse
import contextlib
import io
import struct
import sys
import types
import unittest
from unittest.mock import patch
import ivy

class Journal:
    rows = []
    def __init__(self, _):
        self.path = "memory"
        type(self).rows = []
    def record(self, event, **fields):
        self.rows.append((event, fields))
    def close(self):
        pass

class ProtocolTest(unittest.IsolatedAsyncioTestCase):
    def test_real_clock_calculation(self):
        value = ivy.clock_seconds()
        self.assertIsInstance(value, int)
        self.assertGreater(value, 0)
        self.assertLess(value, 2**32)

    async def run_capture(self, reject_clock=False):
        calls = []
        challenge = bytes.fromhex("90001b01")
        packet = b"\x1e" + struct.pack("<HHHIH", 124, 83, 0, 123456, 84)
        class Scanner:
            @staticmethod
            async def find_device_by_address(address, timeout):
                return types.SimpleNamespace(address=address, name="B810A001020304")
        class Client:
            is_connected = True
            def __init__(self, *args, **kw):
                self.services = self
            def get_characteristic(self, uuid):
                return True
            def __iter__(self):
                return iter([])
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                pass
            async def write_gatt_char(self, uuid, value, response):
                calls.append(("write", value))
                if value[0] == 0x02 and reject_clock:
                    raise RuntimeError("simulated clock rejection")
            async def start_notify(self, uuid, callback):
                calls.append(("subscribe", uuid))
                if uuid == ivy.REPLY:
                    callback(types.SimpleNamespace(uuid=uuid), b"\xa1" + challenge)
                else:
                    callback(types.SimpleNamespace(uuid=uuid), packet)
        module = types.SimpleNamespace(BleakClient=Client, BleakScanner=Scanner)
        args = argparse.Namespace(address=ivy.ADDRESS, output="ignored", password=None,
                                  capture_password=False, scan_timeout=1)
        with patch.dict(sys.modules, {"bleak": module}), patch.object(ivy, "Journal", Journal), patch.object(ivy, "clock_seconds", return_value=123456), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            result = await ivy.capture(args)
        return result, calls

    async def test_exact_apk_handshake_and_known_reading(self):
        result, calls = await self.run_capture()
        self.assertEqual(result, 0)
        self.assertEqual(calls, [
            ("subscribe", ivy.REPLY),
            ("write", bytes.fromhex("202728de65")),
            ("write", b"\x02" + struct.pack("<I", 123456)),
            ("subscribe", ivy.DATA),
        ])
        readings = [fields for event, fields in Journal.rows if event == "reading"]
        self.assertEqual(len(readings), 1)
        self.assertEqual([readings[0][k] for k in ("systolic_mmhg", "diastolic_mmhg", "pulse_bpm")], [124, 83, 84])
        self.assertLess([r[0] for r in Journal.rows].index("rx"), [r[0] for r in Journal.rows].index("reading"))

    async def test_clock_rejection_stops_before_data_subscription(self):
        result, calls = await self.run_capture(reject_clock=True)
        self.assertEqual(result, 1)
        self.assertNotIn(("subscribe", ivy.DATA), calls)
        self.assertTrue(any(event == "error" for event, _ in Journal.rows))

if __name__ == "__main__":
    unittest.main()
