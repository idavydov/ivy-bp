# IVY wrist-monitor BLE protocol

Verified on an IVBPW / LS810-B with firmware 1.01. The device advertises a name
beginning B810A0 and exposes vendor service 7809 (Bluetooth base UUID).
The fixed key and indication ordering below produced a successful live download.

## Session

1. Connect without bonding. Discover service 7809.
2. Enable indications on 8A82 by writing 0200 to its 2902 descriptor.
3. Receive A1 followed by four challenge bytes.
4. Write 20 followed by challenge XOR B7 28 C5 64 to 8A81, with response.
   Example: A1 90 00 1B 01 -> 20 27 28 DE 65.
5. Once that write completes, write 02 plus the four-byte little-endian clock.
6. After that write succeeds, enable indications on 8A91.
7. Receive records. Do not send per-record 22 commands. Disconnect after two
   seconds of data inactivity. The app bounds the overall operation and retry.

No password is derived from the MAC. Enabling data indications before authentication
and using a MAC-derived key both differ from the original app.

## Clock and record

Clock: clone local Calendar, subtract 40 calendar years, take Unix seconds,
add TimeZone.getOffset(15 milliseconds)/1000 and current DST savings/1000.
The Kotlin implementation intentionally follows the original algorithm, including
its legacy timezone behavior. Historical timestamp interpretation remains provisional.

Actual captured record length: 16 bytes; minimum fields occupy 13 bytes.
Header 1E. Little-endian unsigned values:

| Offset | Width | Value |
|---|---|---|
| 1 | 2 | Systolic, mmHg |
| 3 | 2 | Diastolic, mmHg |
| 5 | 2 | Unknown |
| 7 | 4 | Device timestamp |
| 11 | 2 | Pulse, bpm |
| 13 | remainder | Unknown |

Interpret timestamps provisionally as local wall seconds since 2010-01-01.
Retain raw bytes, raw timestamp and phone receipt time. Do not claim timestamp
verification until compared to the real monitor. Unknown formats remain in diagnostics.

## Persistence

Android acknowledges BLE indications automatically. Persist raw bytes immediately;
this cannot guarantee recovery if the process or storage fails before the callback.
Related monitors erase acknowledged readings, but this IVY's erase behavior remains
unverified. Reading and testing should therefore preserve all received data.

## Android hardware verification

Direct GATT connection and authentication succeeded on Pixel 6 / Android 17
(API 37) during a fresh measurement window, followed by one persisted reading.
The laptop remained disconnected. MEM alone is not a reliable Bluetooth wake
instruction; its earlier apparent success was not a controlled wake-mode test.

## First-packet latency confirmed

On a locked Pixel 6 / Android 17, automatic collection with Reliable listening
enabled received its first packet about 28 seconds after authentication and
saved four readings, with the laptop neither scanning nor connected. The
monitor therefore accepts connections before its result is ready; the first
record deadline must cover measurement time. The client now waits 90 seconds
for the first packet and applies the two-second idle deadline only afterward.
