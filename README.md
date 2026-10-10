# SweatIQ - Device App + Control App (simulation demo)

Two independent Android apps that work together:

| App | Install on | What it is |
|---|---|---|
| **SweatIQ Device** (`SweatIQ-Device.apk`) | Phone 1 - the one the evaluator sees | Premium monitoring app: pod connection, live Na+ / K+ / Cl- / sweat-rate cards, trends, readings table, session report (PDF), history. |
| **SweatIQ Control** (`SweatIQ-Control.apk`) | Phone 2 - held out of sight | Operator remote: joins the session, sends manual or automatic simulated values, runs scenarios, pauses / resumes / ends the session, shows a transmission log. |

Both apps are native Kotlin (no Firebase account, no API keys). They exchange data in real time through a free public MQTT broker
(`broker.hivemq.com`, fallback `broker.emqx.io`). Both phones need internet (Wi-Fi or mobile data).

## Get the two APKs (no Android Studio needed)

1. Create a **new, empty** GitHub repository (for example `sweatiq-demo-apps`).
2. Upload everything in this folder to it (keep the `.github/workflows/build-apks.yml` file) and commit to `main`.
3. Open the repo's **Actions** tab. The workflow "Build SweatIQ APKs" starts by itself (about 4-6 minutes).
4. When it is green, open **Releases** (right side of the repo page). It contains `SweatIQ-Device.apk` and `SweatIQ-Control.apk`.
5. Open the Releases page on each phone, download the APK, tap it, and allow "Install unknown apps" when asked.

Prefer Android Studio? Open this folder, wait for Gradle sync, then
`Build > Build Bundle(s) / APK(s) > Build APK(s)` for each module (`deviceApp`, `controlApp`).
Or run `gradle :deviceApp:assembleDebug :controlApp:assembleDebug`.

## Demo workflow

1. **Phone 1 (Device):** open SweatIQ > Start New Session > fill athlete details > Next.
   The *Connect Device* screen shows a 6-digit **session code**; the pod "connects" and the data channel chip turns green.
   Continue > sensors initialise > Live Monitoring opens and says *WAITING FOR DATA*.
2. **Phone 2 (Control, hidden):** open SweatIQ Control > type the 6-digit code > Connect to Session.
   It shows the athlete's name once Phone 1 has announced the session > Open Control Panel.
3. **Auto:** choose a scenario (Normal, High Intensity, Heat & Dehydration, Hydration Recovery, Interval) and an interval (1/2/3/5 s) > Start.
   Phone 1 starts updating by itself: cards, sparklines, trend chart and readings table move in real time.
4. **Manual:** on the Manual tab set e.g. Sodium 47 > Send Reading. The same value appears on Phone 1 within a second.
5. **Events:** Pause / Resume the stream (Phone 1 shows *STREAM PAUSED* and its timer stops), trigger Dehydration / Hydration, then **End Session**.
6. **Phone 1** jumps to the **Session Report** (averages, min/max, readings, duration, interruptions) > Export PDF. Past reports stay in Session History.

## How the two apps talk

```
Phone 2  SweatIQ Control --publish--> broker  sweatiq-grassion-demo/v1/<code>/reading   (retained)
Phone 2  SweatIQ Control --publish--> broker  sweatiq-grassion-demo/v1/<code>/cmd       START | PAUSE | RESUME | END
Phone 1  SweatIQ Device  --publish--> broker  sweatiq-grassion-demo/v1/<code>/state     WAITING | LIVE | PAUSED | ENDED (retained)
Phone 1  SweatIQ Device  <--subscribe-- reading, cmd, hello      Phone 2 <--subscribe-- state
```

Reading payload: `{seq, ts, na, k, cl, rate, skinT, ambT, scenario, src:"SIMULATION"}`.
`seq` lets Phone 1 count missing / out-of-order readings (shown as "interruptions" in the report).

## Honest labelling

All values are simulated. Phone 1 shows a **DEMO MODE** badge and the report carries *SIMULATED DATA - NOT A BIOSENSOR MEASUREMENT*.
When the real pod exists, replace the "Control -> broker -> Device" path with a BLE reader inside the Device app (`onRemote("reading", ...)` is the single entry point).

## Notes

* The public broker is open: use it for demos only, never for real athlete data. A private broker or Firebase can replace `Link.kt` later.
* If a venue blocks port 1883/8883, use a mobile hotspot.
* Debug-signed APKs are fine for demos and sideloading.
