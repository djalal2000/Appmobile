# PEMESA

PEMESA (*Pénibilité Métier Santé*) is a research application designed to collect and annotate wearable sensor data for the study of work-related strain among healthcare professionals.

The Android mobile application manages participants, activity annotations, fatigue ratings, and acquisition settings. The Wear OS application collects sensor data and sends them directly to InfluxDB over HTTPS.

## Features

- Real-time activity annotation and subjective fatigue assessment.
- Configurable sensor selection, sampling frequency, and transmission interval.
- Smartwatch association and configuration synchronization.
- Temporary buffering in RAM and automatic transmission retries.
- Connection monitoring, thermal monitoring, and technical diagnostics.
- English and French interfaces.

## Collected data

| Signal | InfluxDB fields |
| --- | --- |
| Acceleration | `acceX`, `acceY`, `acceZ` |
| Angular velocity | `gyroX`, `gyroY`, `gyroZ` |
| Gravity | `gravityX`, `gravityY`, `gravityZ` |
| Linear acceleration | `linAccX`, `linAccY`, `linAccZ` |
| Rotation vector | `rotX`, `rotY`, `rotZ`, `rotW` |
| Magnetic field (optional) | `magX`, `magY`, `magZ` |
| Heart rate | `bpm` |
| Steps | `steps` |
| Ambient light | `lux` |
| Atmospheric pressure | `hPa` |
| Subjective fatigue (0–5) | `fatigue_level` |

The inertial sampling target is configurable at 25, 50, or 100 Hz. The default is 50 Hz with a 10-second transmission interval. Effective sampling rates and sensor availability depend on the device. Physiological and environmental measurements are event-driven; fatigue is recorded at session start and when its value changes.

## Installation

### Requirements

- An Android smartphone and a Wear OS smartwatch.
- JDK 17 and Android SDK 36 to build the applications.
- An InfluxDB server accessible over HTTPS.

### Build from source

Open the project in Android Studio or build both applications from the repository root:

```sh
./gradlew :app:assembleDebug :wear:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Install the mobile APK on the phone and the Wear OS APK on the watch. To install on the watch using ADB, enable wireless debugging and use the IP address and ports displayed on the watch:

```sh
adb pair <IP>:<PAIRING_PORT>
adb connect <IP>:<DEBUG_PORT>
adb devices
```

Install the generated watch APK using `adb install -r <APK_PATH>`.

## Configuration and use

1. Open PEMESA on both devices and grant the required sensor permissions on the watch.
2. Associate the watch in **Settings > Smartwatch management**.
3. In **Settings > InfluxDB**, enter the HTTPS server URL, organization, bucket names, and API write token.
4. Save the configuration, confirm synchronization, and select **Test saved connection**.
5. Select the required sensors and acquisition settings.
6. Enter the participant identifier, select the profession, and start the recording.

Activity annotations and fatigue ratings can be updated during recording. Use Pause, Resume, and Stop to control the session.

## InfluxDB

The default bucket names are:

| Bucket | Measurements |
| --- | --- |
| `activity` | `inertial_data`, `activity` |
| `wearable` | `heart_rate`, `steps`, `light`, `pressure`, `fatigue_level` |

Inertial samples retain their acquisition timestamp, activity label, participant identifier, session identifier, watch identifier, and sequence number.

No server credentials are embedded in the current application source. The active token is stored using Android Keystore-backed encryption. Use a dedicated token with write access to the selected buckets, and a separate read token for analysis. Revoke any previously committed credentials before distributing the repository or APKs.

## Connection interruptions

The watch can continue an active recording when the phone disconnects, provided the watch application remains running. The last confirmed activity label is retained until a new annotation is received.

During Internet outages, pending measurements are buffered in RAM and transmission is retried automatically. The activity queue holds up to 32 waiting batches, and the wearable queue holds up to 2,048 pending records. Each in-flight batch has a five-minute retry budget. Measurements may be lost if the buffers fill, retries fail, or the watch process stops.

Stop confirms that acquisition has ended and partial batches have been queued; it does not confirm that every measurement has reached InfluxDB. No measurement data are permanently stored by the application on the watch.

## Tests

Run the JVM unit tests for both modules:

```sh
./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest
```

Before experimental deployment, validate effective sampling rates, annotation timing, transmission integrity, connectivity recovery, battery endurance, and thermal behavior on the target devices. Record the software revision and acquisition configuration for reproducibility.

## Contact

Development team: djalal.bouchekif@irit.fr

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
