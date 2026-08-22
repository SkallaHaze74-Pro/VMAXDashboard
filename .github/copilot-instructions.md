# GitHub Copilot instructions

Read and follow [`AGENTS.md`](../AGENTS.md) before proposing or changing code.

Preserve the V-Core/BT638/DA1A versus Hyena/vendor evidence boundary. Keep Deep READ records separate from notification telemetry. AI output is advisory only and must never trigger BLE writes, tuning, firmware changes, invented authentication, or automatic decoder confirmation. Keep raw telemetry and generated decoder output on `telemetry-data`, not `main`.
