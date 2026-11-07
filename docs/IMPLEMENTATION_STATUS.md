# Implementation Status

## Approved operating baseline
- Initial handset: Samsung Galaxy Note 10.
- Initial SIM count: one physical SIM.
- Dual-SIM capability remains a planned expansion.
- Keep telephony behind a SIM abstraction; do not hard-code a single subscription ID.
- No changes to the TEST4 core application or financial modules.

## Phase 0 — Repository foundation
- [x] Android project structure
- [x] Kotlin / Compose application shell
- [ ] Android build verification
- [ ] Gradle wrapper checked in

## Phase 1 — Device and platform validation
- [ ] Confirm exact Note 10 model identifier and Android version
- [ ] Verify telephony and active subscription discovery
- [ ] Verify runtime SEND_SMS permission flow
- [ ] Verify foreground-service behavior on target Android version
- [ ] Verify SIM send result and delivery-report behavior with a controlled test number

## Phase 2 — Gateway protocol
Blocked until the TEST4 backend API contract is provided and reviewed:
- device enrollment and revocation
- authenticated polling
- request expiry and replay protection
- send-result acknowledgement
- heartbeat and device status

## Security invariants
- OTP generation and verification stay on TEST4 Backend.
- Never persist OTP or full message body.
- Never log OTP or full message body.
- Use Android Keystore for device private key material.
- HTTPS only; reject cleartext endpoints.
- Do not retry an SMS send blindly when the outcome is ambiguous.
- A successful Android send callback means submitted to the telephony stack, not confirmed delivered.

## Current limitations
The current dashboard is a non-operational scaffold. It does not inspect the SIM, poll a backend, or send SMS. Those features will be implemented only with explicit, testable boundaries and verified against the physical Note 10.
