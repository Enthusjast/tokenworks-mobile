# Third-party notices

This project includes or depends on the following third-party software.

## npm dependencies (Host plugin and tooling)

| Package | License | Notes |
| --- | --- | --- |
| `@deepseek-ai/dsh` and related `@deepseek-ai/*` packages | MIT | Native API bindings locked to `0.2.1-rc.5`; build-time type snapshots only. |
| `typescript` | Apache-2.0 | Build tooling; not distributed to end users. |
| `tsx`, `zod`, `ajv`, `ws` | MIT | Build/transport tooling. |
| `yaml` | ISC | Build tooling. |

Go dependencies are declared in `services/remote/go.mod`; their licenses are carried by the Go module proxy metadata.

## Reference projects

Design review references (no code copied):

| Project | License | Influence |
| --- | --- | --- |
| [dsh-mobile](https://github.com/saya-ch/dsh-mobile) | Apache-2.0 | Architecture comparison in `IMPROVE_PLAN.md`; LAN gateway design inspiration. |
| [ds-harness-remote](https://github.com/liguobao/ds-harness-remote) | MIT (plugin) | E2E (Noise IK) and transport fallback design references. |
| [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) | AGPL-3.0 | Mobile UX comparison only; no code reused. |

No source code from the reference projects is included in this repository.
