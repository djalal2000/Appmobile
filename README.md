# 📊 PEMESA

**PEMESA** (*Pénibilité Métier Santé*) is an application developed as part of a research project on **work-related strain**, based on **data collection and analysis from IoT devices**.

---

## 📁 Project Structure

```text
PEMESA/
├── app/          # Source code for the Android mobile application
├── wear/         # Source code for the Wear OS application
└── README.md
```

---

## 📚 Table of Contents

- [Overview](#overview)
- [Installation](#installation)
  - [Preparing the Device](#preparing-the-device)
  - [Wireless Debugging](#wireless-debugging)
- [Features](#features)
  - [Mobile Application](#mobile-application)
  - [Wear OS Application](#wear-os-application)
    - [Motion Sensors](#motion-sensors)
    - [Environmental Sensors](#environmental-sensors)
    - [Physiological Sensors](#physiological-sensors)
- [Sampling Frequency](#sampling-frequency)
- [Data Transmission](#data-transmission)
- [Best Practices](#best-practices)
- [Support](#support)
- [License](#license)

---

## 🧠 Overview

**PEMESA** facilitates the **collection**, **management**, and **analysis** of physiological, inertial, and environmental data for monitoring work-related strain.  

The application allows you to:
- Collect **inertial data** (accelerometer, gyroscope, etc.)
- Measure **physiological indicators** (heart rate)
- Monitor **environmental parameters** (light, pressure, etc.)
- **Remotely control** the smartwatch from the mobile app
- **Label user activities in real time**

### The ecosystem includes:
📱 **Android Mobile App** – control interface and session management  
⌚ **Wear OS App** – sensor data capture and transmission  

---

## ⚙️ Installation

### Preparing the Device

#### Enable Developer Mode

1. Open **Settings > About Device > Build Number** (or **Software Version**).  
2. Tap the **Build Number 7 times** to enable **Developer Mode**.  
3. Then activate **Developer Options**.

---

### Wireless Debugging

Ensure that your **computer** and **smartwatch** are on the **same Wi-Fi network**.

1. Enable **Wireless Debugging** in **Developer Options**.  
2. Open a terminal on your computer and navigate to `android_sdk/platform-tools`.  
3. On the watch, retrieve the **IP address**, **port**, and **pairing code** via *Pair device with pairing code*.  
4. Run the following commands:  

```bash
adb pair <IP>:<PORT>
adb connect <IP>:<PORT>
```
5. Verify the connection:  
```bash
adb devices
```

---

## 🚀 Features

### Mobile Application

- Remote control of data collection
- Real-time activity labeling

#### Main Interfaces
- **Home Screen:** enter `person_id` and select job/profile
- **Control Screen:** buttons **Start**, **Pause**, **Stop**, activity selection, and fatigue scale (0-5)

### Wear OS Application

- Executes instructions from the mobile app
- Configures and collects continuous sensor data

#### Motion Sensors
- `TYPE_ACCELEROMETER`, `TYPE_GYROSCOPE`, `TYPE_GRAVITY`, `TYPE_ROTATION_VECTOR`, `TYPE_LINEAR_ACCELERATION`, `TYPE_STEP_COUNTER`

#### Environmental Sensors
- `TYPE_LIGHT`, `TYPE_PRESSURE`

#### Physiological Sensors
- `TYPE_HEART_RATE`

---

## ⏱️ Sampling Frequency

- Sensors `ACCELEROMETER`, `GYROSCOPE`, `GRAVITY`, `ROTATION_VECTOR`, `LINEAR_ACCELERATION`: **50 Hz** (adjustable)  
- Sensors `STEP_COUNTER`, `LIGHT`, `PRESSURE`, `HEART_RATE`: measured on each available event

---

## ☁️ Data Transmission

Data is transmitted **in real time** via Wi-Fi to an **InfluxDB** database.

Configuration parameters:

```bash
INFLUXDB_URL="https://<your-influxdb-instance>"
INFLUXDB_TOKEN="<your-token>"
INFLUXDB_ORG="<your-organization>"
INFLUXDB_BUCKET="<your-bucket>"
```

---

## 📝 Best Practices

- Keep a maximum distance of 6m between the phone and watch (Bluetooth) to avoid automatic collection interruption. A notification will be sent if disconnected.  
- Ensure the watch is properly positioned on the participant's wrist.  
- Check the watch's battery level before each session.

---

## 💡 Support

- Refer to the official project documentation.  
- Contact the development team: **djalal.bouchekif@irit.fr**

---

## ⚖️ License

This project is distributed under the **MIT License**. Free to use, modify, and redistribute under the license terms.

