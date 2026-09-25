# ADR-005 — Serialization & Transport

Status: ACCEPTED
Date: 2026-08-27
Level: Implementation Decision

## Context

Protocol Architecture §31 explicitly leaves format (JSON/CBOR/Protobuf)
and transport (HTTP/QUIC/WebSocket/mesh/etc.) undefined at the Protocol
level, requiring only the semantic envelope (Protocol Architecture §17).

## Decision

- **Serialization**: JSON for MVP (human-inspectable, simplifies
  debugging of the local event log and audit trail).
- **Transport**: HTTPS/REST for the first sync/federation-stub
  implementation between nodes.
- **Transport abstraction**: the Application layer talks to a
  `SyncTransport` port (send/receive event batches); HTTPS/REST is one
  adapter behind it. No Domain or Application code references HTTP
  directly.

## Protocol Envelope (Protocol Architecture §17, MVP subset)

```json
{
  "message_id": "...",
  "protocol": "P05" ,
  "version": "0.1",
  "timestamp": "...",
  "sender": "...",
  "sender_identity": "...",
  "subject": "...",
  "action": "...",
  "context": "...",
  "authority_reference": "...",
  "provenance": "...",
  "payload": {},
  "expected_result": "...",
  "correlation_id": "..."
}
```

## Rationale

JSON + REST is the lowest-friction choice that lets Phase 3 focus on
domain correctness rather than binary protocol tooling. The transport
abstraction preserves Protocol Independence (Protocol Architecture §23)
so CBOR/Protobuf or a P2P transport can replace it later without
touching Domain/Application code.

## Consequences

- Larger payload size than a binary format — acceptable at MVP scale
  (2–5 PERSON, single Core).
- REST implies a request/response client-server shape for the first
  sync adapter; true peer-to-peer mesh sync is deferred (tracked as a
  FUTURE item, not MVP).

## Reversibility

Fully replaceable; isolated behind `SyncTransport` and a
`MessageCodec` port.
