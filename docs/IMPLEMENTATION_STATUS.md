# Implementation Status

## Approved operating baseline
- Initial handset: Samsung Galaxy Note 10.
- Target handset OS: Android 11 (API 30).
- Initial deployment: one active SIM.
- Keep telephony behind a SIM abstraction so Dual-SIM can be added later.
- No changes to the TEST4 core application or financial modules.
- No external SMS provider.

## Phase 0 — Repository foundation
- [x] Android project structure and Kotlin / Compose dashboard
- [x] Android CI workflow with SDK discovery, serialized runs and APK artifact upload
- [ ] Verified successful Android build and unit-test run
- [ ] Gradle wrapper checked in

## Phase 1 — Device and SMS platform
- [x] SIM abstraction (`SimManager`) supports a list of subscriptions
- [x] Android subscription discovery implementation
- [x] Dashboard runtime permission request for READ_PHONE_STATE
- [x] Dashboard displays discovered SIM state
- [x] Android SMS dispatch adapter with SEND_SMS permission guard
- [x] Metadata-only SMS job state model
- [x] State transition tests, including protection against retrying DELIVERY_UNKNOWN
- [ ] Explicit runtime SEND_SMS permission request in the user-initiated test flow
- [ ] Verify SIM discovery on the physical Note 10
- [ ] Verify SMS dispatch and callback behavior using a controlled test number
- [ ] Verify foreground-service and reboot behavior on the physical handset

## Phase 2 — Gateway protocol
Blocked until the TEST4 backend API contract is provided and reviewed:
- device enrollment and revocation
- authenticated HTTPS polling
- request expiry and replay protection
- send-result acknowledgement
- heartbeat and device status

## Security invariants
- OTP generation and verification stay on TEST4 Backend.
- OTP, message body and destination must not be persisted or written to logs.
- Persist only job metadata and opaque request identifiers.
- Use Android Keystore for device private key material.
- HTTPS only; reject cleartext endpoints.
- Never blindly retry when the SMS submission outcome is ambiguous.
- `SubmittedToAndroid` means accepted by the Android telephony API; it does not confirm carrier delivery or recipient receipt.
- The current SMS adapter is not invoked by the UI and no SMS is sent automatically.

## Current limitations
The dashboard can request SIM-read permission and display subscription state when run on a compatible device. The SMS adapter and metadata state machine are implemented but are not yet connected to a queue, foreground service or backend. Physical-device verification and successful CI build evidence are still pending.
