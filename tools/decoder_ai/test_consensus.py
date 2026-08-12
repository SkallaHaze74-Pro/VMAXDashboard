import csv
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("consensus.py")
spec = importlib.util.spec_from_file_location("decoder_consensus", MODULE_PATH)
consensus = importlib.util.module_from_spec(spec)
sys.modules["decoder_consensus"] = consensus
spec.loader.exec_module(consensus)

LIVE_HEADER = [
    "relative_ms", "timestamp_ms", "speed_kmh_candidate", "battery_percent", "voltage_v", "current_a",
    "power_w", "motor_temp_c", "battery_temp_c", "trip_km", "odometer_km", "drive_raw_1505_b7",
    "motor_load_raw_be", "battery_state_raw_1509_b6", "accessory_raw_b0", "accessory_raw_b3", "source_channel"
]
RAW_HEADER = ["relative_ms", "timestamp_ms", "channel", "meaning", "length", "packet_no", "changed_bytes", "hex"]


class ConsensusTests(unittest.TestCase):
    def make_ride(self, root: Path, name: str, observations: int, updated_at: int, numeric: bool) -> Path:
        ride = root / "2026-08-13" / name
        ride.mkdir(parents=True)
        (ride / "manifest.json").write_text(json.dumps({"created_at_ms": updated_at}), encoding="utf-8")
        profile = {
            "format": "VMAX_LEARNING_PROFILE_V1",
            "updatedAt": updated_at,
            "candidates": [{
                "key": "Bremse im Stand|151D|2",
                "model": "BT638",
                "label": "Bremse im Stand",
                "channel": "151D",
                "byteIndex": 2,
                "confidence": 97,
                "observations": observations,
                "lastBefore": 0,
                "lastAfter": 1,
                "updatedAt": updated_at,
                "status": "candidate"
            }]
        }
        (ride / "Lernprofil.json").write_text(json.dumps(profile), encoding="utf-8")
        if numeric:
            with (ride / "BLE_Rohdaten.csv").open("w", newline="", encoding="utf-8") as raw_file, \
                    (ride / "Live_Telemetrie.csv").open("w", newline="", encoding="utf-8") as live_file:
                raw_writer = csv.DictWriter(raw_file, fieldnames=RAW_HEADER, delimiter=";")
                live_writer = csv.DictWriter(live_file, fieldnames=LIVE_HEADER, delimiter=";")
                raw_writer.writeheader()
                live_writer.writeheader()
                for index in range(60):
                    speed = 5.0 + index * 0.25
                    raw_speed = int(round(speed * 10))
                    relative = index * 100
                    payload = raw_speed.to_bytes(2, "big") + bytes([index % 4, 0])
                    raw_writer.writerow({
                        "relative_ms": relative,
                        "timestamp_ms": updated_at + relative,
                        "channel": "151D",
                        "meaning": "",
                        "length": 4,
                        "packet_no": index + 1,
                        "changed_bytes": "",
                        "hex": "-".join(f"{value:02X}" for value in payload),
                    })
                    row = {column: "" for column in LIVE_HEADER}
                    row.update({
                        "relative_ms": relative,
                        "timestamp_ms": updated_at + relative,
                        "speed_kmh_candidate": speed,
                        "source_channel": "151D",
                    })
                    live_writer.writerow(row)
        return ride

    def test_repeated_marker_promotes_brake_rule(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "fahrdaten"
            self.make_ride(root, "Messfahrt_2026-08-13_10-00-00", 1, 1000, False)
            self.make_ride(root, "Messfahrt_2026-08-13_11-00-00", 2, 2000, False)
            profile = consensus.analyze(root)
            rule = next(rule for rule in profile["rules"] if rule["signal"] == "brakeActive")
            self.assertEqual("confirmed", rule["status"])
            self.assertEqual(1, rule["activeValue"])
            self.assertEqual(0, rule["inactiveValue"])

    def test_numeric_correlation_finds_second_speed_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "fahrdaten"
            self.make_ride(root, "Messfahrt_2026-08-13_10-00-00", 1, 1000, True)
            self.make_ride(root, "Messfahrt_2026-08-13_11-00-00", 2, 2000, True)
            profile = consensus.analyze(root)
            rule = next(rule for rule in profile["rules"] if rule["signal"] == "speedKmh" and rule["channel"] == "151D")
            self.assertEqual("confirmed", rule["status"])
            self.assertEqual("u16be", rule["encoding"])
            self.assertAlmostEqual(0.1, rule["scale"], places=4)
            self.assertAlmostEqual(0.0, rule["bias"], delta=0.01)


if __name__ == "__main__":
    unittest.main()
