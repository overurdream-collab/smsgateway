# SMSGateway — TEST4 SMS Gateway

Independent Android SMS gateway for TEST4.

## Project status
- Specification baseline: TEST4 SMS Gateway v1.2
- Implementation status: foundation scaffold
- Initial device: Samsung Galaxy Note 10, single SIM
- Future capability: dual-SIM support through an abstraction layer
- Android client: Kotlin + Jetpack Compose

## Scope and safety
- OTP generation and verification remain the responsibility of TEST4 Backend.
- The Android application must never persist OTP values or message bodies.
- Never commit API tokens, private keys, production endpoints, or customer data.
- The repository is public; all configuration examples must use placeholders.
- SMS sending must only be enabled for authenticated, authorized TEST4 requests.
- Carrier terms, local laws, and operator rate limits must be respected.

## Architecture direction
The Android client will be separated into presentation, domain, data, platform telephony, secure device identity, and background gateway runtime.

SIM selection is abstracted so the initial single-SIM implementation can later support dual-SIM devices without rewriting queue or networking logic.

## Build
Open this project in Android Studio (JDK 17, Android SDK 35) and sync Gradle.
A Gradle wrapper will be added with the first verified Android build environment.

## Important
This initial commit is project scaffolding only. It does not yet send SMS or connect to a production TEST4 backend. Those capabilities require the agreed API contract, device-side permission flow, and hardware validation.
