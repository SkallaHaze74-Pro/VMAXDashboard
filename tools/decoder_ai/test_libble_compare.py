#!/usr/bin/env python3
import tempfile
from pathlib import Path

from libble_compare import analyze_ride


def write(path: Path, text: str):
    path.write_text(text, encoding="utf-8")


def main():
    with tempfile.TemporaryDirectory() as tmp:
        ride = Path(tmp) / "Messfahrt_test"
        ride.mkdir()
        write(
            ride / "BLE_Rohdaten.csv",
            "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex\n"
            "0;1000;1505;Fahrleistung;12;1;0;03-E8-03-B6-00-7B-00-7B-01-2C-00-32\n"
            "10;1010;1509;Akku-Livedaten;11;1;0;05-DC-00-FA-50-BD-74-05-78-00-48\n",
        )
        write(
            ride / "Live_Telemetrie.csv",
            "relative_ms;timestamp_ms;speed_kmh_candidate;battery_percent;voltage_v;current_a;power_w;motor_temp_c;battery_temp_c;trip_km;odometer_km;drive_raw_1505_b7;motor_load_raw_be;battery_state_raw_1509_b6;accessory_raw_b0;accessory_raw_b3;source_channel\n"
            "0;1000;12.3;80;48.5;1.5;72;;25.0;;;;;;;1505\n"
            "10;1010;12.3;80;48.5;1.5;72;;25.0;;;;;;;1509\n",
        )
        result = analyze_ride(ride)
        fields = result["fields"]
        assert fields["1505.speed_kmh"]["match_percent"] == 100.0, fields
        assert fields["1509.current_A"]["match_percent"] == 100.0, fields
        assert fields["1509.battery_temp_C"]["match_percent"] == 100.0, fields
        assert fields["1509.soc_percent"]["match_percent"] == 100.0, fields
        assert fields["1509.voltage_V"]["match_percent"] == 100.0, fields
        assert fields["1509.direct_power_W"]["match_percent"] == 100.0, fields
        assert abs(fields["1505.powerA_W"]["mean"] - 100.0) < 1e-9
        assert abs(fields["1505.torque_Nm"]["mean"] - 1.23) < 1e-9
        assert abs(fields["1505.rpm"]["mean"] - 300.0) < 1e-9
        print("libble ground-truth comparator: OK")


if __name__ == "__main__":
    main()
