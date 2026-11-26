# PEMESA

PEMESA (*Pénibilité Métier Santé*) is a research application for collecting and annotating wearable sensor data in studies of work-related strain among healthcare professionals. It consists of an Android mobile application and a Wear OS acquisition application.

## Repository structure

| Directory | Purpose |
| --- | --- |
| `app/` | Mobile interface, participant identification, activity annotation, device association, and configuration |
| `wear/` | Sensor acquisition, session control, buffering, and InfluxDB transmission |
| `tools/pemesa-migration/` | Historical migration utilities |
| `.github/workflows/` | Build and test automation |

## Architecture

The mobile application associates with a selected watch and sends acquisition commands and configuration revisions through the Wear OS Data Layer. The watch verifies the saved configuration before starting a session. During acquisition, sensor callbacks are processed on a dedicated thread. Inertial samples are annotated on the watch using the current activity label, participant identifier, session identifier, watch identifier, and acquisition timestamp.

The watch submits data directly to InfluxDB over HTTPS. Independent, bounded FIFO queues in RAM separate activity and wearable transmissions. No measurement data are permanently stored by the application on the watch. Configuration is persisted separately; custom API credentials are protected using Android Keystore-backed encryption.

## Acquisition

The configurable inertial sampling targets are 25, 50, and 100 Hz. The default is 50 Hz with a 10-second activity transmission interval. The actual hardware sensor event rate and effective sampling rate depend on the device and operating conditions.

| Signal | Android sensor | InfluxDB fields |
| --- | --- | --- |
| Acceleration | `TYPE_ACCELEROMETER` | `acceX`, `acceY`, `acceZ` |
| Angular velocity | `TYPE_GYROSCOPE` | `gyroX`, `gyroY`, `gyroZ` |
| Gravity | `TYPE_GRAVITY` | `gravityX`, `gravityY`, `gravityZ` |
| Linear acceleration | `TYPE_LINEAR_ACCELERATION` | `linAccX`, `linAccY`, `linAccZ` |
| Rotation vector | `TYPE_ROTATION_VECTOR` | `rotX`, `rotY`, `rotZ`, `rotW` |
| Magnetic field (optional; disabled by default) | `TYPE_MAGNETIC_FIELD` | `magX`, `magY`, `magZ` |
| Heart rate | `TYPE_HEART_RATE` | `bpm` |
| Steps | `TYPE_STEP_COUNTER` | `steps` |
| Ambient light | `TYPE_LIGHT` | `lux` |
| Atmospheric pressure | `TYPE_PRESSURE` | `hPa` |
| Subjective fatigue | Manual input, scale 0–5 | `fatigue_level` |

The inertial timer samples the latest valid sensor readings. Heart rate, step count, light, and pressure are event-driven; heart rate and light are recorded on changes. Fatigue is recorded at session start and on subsequent changes when enabled. Sensor availability and permissions depend on the device. All signals can be selected in the mobile settings.

## Data transmission and recovery

The default InfluxDB buckets are `activity` and `wearable`. The activity bucket contains the `inertial_data` and `activity` measurements. The wearable bucket contains `heart_rate`, `steps`, `light`, `pressure`, and `fatigue_level`. The configuration supports a custom HTTPS URL, organization, distinct bucket names, and write token. Use study-specific credentials; do not disclose tokens in publications or shared logs.

Transient HTTP and network failures trigger bounded retries with increasing delays, reusing the original payload and timestamps. A batch in transmission has a five-minute recovery budget. The activity queue holds up to 32 waiting batches; the wearable queue is limited to 2,048 pending records. Data can be lost if the queues fill, recovery fails, or the watch process stops. A successful Stop acknowledgement confirms acquisition has stopped and partial batches have been queued, not that all measurements have been committed to InfluxDB.

If the phone disconnects during acquisition, the watch may continue collecting and transmitting while its application remains running. Samples retain the last acknowledged activity label until a new annotation reaches the watch. If Internet access is interrupted but the phone-watch link remains available, new annotations can still be applied. The mobile interface displays transmission and connection status; diagnostics include the Android thermal status and a bounded technical journal.

## Build and installation

The Android modules use compile SDK 36 and target SDK 36. The continuous integration workflow uses JDK 17.

1. Open the repository in Android Studio and install the required Android SDK.
2. Enable developer options and wireless debugging on the test device and watch.
3. Connect the watch using Android Debug Bridge (ADB), substituting the addresses and ports shown on the watch:

   ```sh
   adb pair <IP>:<PAIRING_PORT>
   adb connect <IP>:<DEBUG_PORT>
   adb devices
   ```

4. Build both modules and run the JVM tests:

   ```sh
   ./gradlew :app:assembleDebug :wear:assembleDebug :app:testDebugUnitTest :wear:testDebugUnitTest
   ```

   On Windows, use `gradlew.bat`.

5. Install the mobile and Wear OS applications on their respective devices. Open PEMESA on the watch, grant the required sensor permissions, and associate it using the mobile application.
6. Configure InfluxDB, select sensors and acquisition settings, and verify the watch configuration confirmation before starting a session.

## Experimental validation

Before deploying PEMESA in a study, verify sensor availability, effective sampling rate, activity annotation timing, clock alignment, transmission integrity, battery endurance, and thermal behavior on the target hardware. Include controlled phone-watch disconnection, Internet outage, reconnection, and Stop tests. The JVM tests and successful Android builds do not replace physical-device or end-to-end experimental validation.

For reproducibility, record the repository commit, device model, software versions, selected sensors, sampling target, transmission interval, and study configuration. Do not include participant identifiers or API credentials in public materials.

## Contact

Development team: djalal.bouchekif@irit.fr

## License

See the [LICENSE](LICENSE) file (Apache License, Version 2.0).
