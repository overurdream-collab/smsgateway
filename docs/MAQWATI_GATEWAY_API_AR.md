# Maqwati Link integration — Android SMSGateway

This repository is the Android gateway, originally scaffolded for TEST4. The new client at `app/src/main/java/com/test4/smsgateway/data/remote/MaqwatiGatewayApi.kt` supports Maqwati Link's proposed API, without replacing TEST4 support.

## Configuration (never commit real values)
- `GATEWAY_BASE_URL=https://your-otp-service.example`
- `GATEWAY_DEVICE_ID=<approved-device-id>`
- `GATEWAY_ANDROID_KEY_ALIAS=<keystore-rsa-key-alias>`
- Enrollment: generate a 2048+ bit RSA signing key in Android Keystore, register its public key using a protected administrative channel, and enable the device in `sms_gateway_devices`.
- Signing canonical string: `POST\\n/path\\n<epoch-milliseconds>\\n<uuid-nonce>\\n<raw-json-body>`. Signature: SHA256withRSA, base64.
- `POST /v1/gateway/poll` body `{}`; response `{job:null}` or `{job:{id,recipient,text}}`.
- `POST /v1/gateway/heartbeat` body `{}`.
- `POST /v1/gateway/result` body `{jobId,status,simSlot}` with status sent/failed/delivered.

## Important incomplete integration
- This is an API transport client only. It is NOT yet called from a background worker and does NOT send SMS.
- A worker must parse poll response, choose an active subscription ID, send with Android SmsManager, await per-part sent callbacks, and only then report `sent`; otherwise report `failed`.
- Implement registration UI, secure device provisioning, retries, no plaintext logging, queue idempotency, and proper runtime SEND_SMS permission.
- Never store OTP text in app database/logs. Keep TEST4 and Maqwati environment credentials isolated.
- The Maqwati server counterpart is in `overurdream-collab/Qat-Link-interface` PR #44, not yet tested or deployed.
