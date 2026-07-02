## Appcircle Publish

Upload an application binary to an Appcircle **Publish** profile and/or trigger its publish flow (app store publishing) directly from your Jenkins pipeline.

Learn more about [Appcircle Publish](https://appcircle.io/publish-to-stores?utm_source=jenkins&utm_medium=plugin&utm_campaign=publish).

## What the plugin does

The build step has two independent switches — `upload` and `publish` — both default to `false`. **You must enable at least one.** Create the Publish profile in Appcircle first; the step targets it by name (profile names are unique per platform).

| `upload` | `publish` | Behavior |
|:--------:|:---------:|----------|
| `true`  | `false` | Upload the binary at App Path as a new app version on the profile. |
| `false` | `true`  | Trigger the publish flow for the profile's **current release candidate**. |
| `true`  | `true`  | Upload the binary, **mark the new version as release candidate**, then trigger the publish flow for it. |
| `false` | `false` | Error — nothing to do. |

**Rules:**

- **In-progress guard:** if a publish is already running for the target profile, the step does **not** start a new one and fails the build.
- **Release candidate:** when both `upload` and `publish` are enabled, the freshly uploaded version is automatically marked as the release candidate before it is published. In publish-only mode, the profile's existing release candidate is published.
- **Progress:** while publishing, the step polls the publish status and prints step-by-step progress (with status icons) until the flow succeeds or fails.

> **Manual-approval steps:** if the profile's publish flow contains a manual step (e.g. "Get Approval via Email"), the flow waits for that action and the plugin keeps polling until it completes or the poll times out. For CI use, prefer publish flows without manual gates.

## How to use

Add the **Appcircle Publish** build step to a Freestyle job (or use the `appcirclePublish` symbol in a Pipeline). Configure:

- **Personal API Token** — Appcircle Personal API Token used to authenticate Appcircle services.
- **Platform** — `iOS` or `Android`.
- **Publish Profile Name** — name of the Publish profile to target (unique per platform).
- **Upload binary** — upload App Path as a new app version. (At least one of Upload / Trigger publish flow must be checked.)
- **Trigger publish flow** — start the profile's publish flow.
- **App Path** — path to the application file; required when Upload is checked. iOS: `.ipa`; Android: `.apk` / `.aab`.
- **Self-Hosted Appcircle** (advanced) — optional `Auth Endpoint` / `API Endpoint`; default to the Appcircle cloud.

Pipeline example:

```groovy
appcirclePublish personalAPIToken: env.AC_PERSONAL_API_TOKEN,
                 platform: 'ios',
                 publishProfile: 'My Publish Profile',
                 upload: true,
                 publish: true,
                 appPath: 'app.ipa'
```

### Self-Hosted Appcircle

Set the optional `authEndpoint` / `apiEndpoint` to target a self-hosted Appcircle installation. Both default to the Appcircle cloud (`https://auth.appcircle.io` / `https://api.appcircle.io`).

> **Self-signed or private CA certificates:** If your self-hosted Appcircle server uses a self-signed certificate (or one issued by a private/internal CA), requests will fail certificate validation. The plugin does not disable TLS verification. Trust the server's CA on the Jenkins agent's JVM (import it into the `cacerts` truststore, or set `-Djavax.net.ssl.trustStore`).

## References

For more detailed instructions and support, visit the [Appcircle Publish documentation](https://docs.appcircle.io/publish-to-stores-module).
