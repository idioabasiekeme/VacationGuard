# VacationGuard — Intelligent Smart Home Security System

Project: **Design and Implementation of an Intelligent Smart Home Security System for
Detecting Criminal Intrusion and Protecting Homeowners Using IoT, Sensors,
Microcontrollers, and Smart Surveillance** — Vacation Home Monitoring use case
(*owner watches property remotely from another city*).

## How it works
One Android app, two roles, linked by a shared **Home ID** over a free public MQTT
cloud broker (broker.hivemq.com) — so it works between different cities:

| Role | Device | What it does |
|------|--------|--------------|
| **Home Mode** | old phone left at the house | Camera motion detection (frame differencing), publishes alerts + JPEG snapshots, sounds siren on command |
| **Owner Mode** | owner's phone anywhere | Online/offline status, intrusion notifications with snapshots, request live snapshot ("View now"), trigger/stop siren remotely |

Topics: `vacationguard/<homeId>/{status, alert, snapshot, cmd}` (heartbeat every 20 s,
last-will OFFLINE, 15 s alert cooldown, snapshots downscaled to 640 px JPEG).

A hardware home unit is also included: `esp32/VacationGuard_ESP32CAM.ino`
(AI-Thinker ESP32-CAM + PIR sensor + buzzer siren, same MQTT topics — the owner app
works with it unchanged).

## Build
1. Open this folder in **Android Studio**, let Gradle sync.
2. Run on two phones (both need internet; home phone needs a camera).
3. Enter the **same Home ID** on both → one taps **Home Mode**, the other **Owner Mode**.

Or download the APK from **Actions → latest run → VacationGuard-debug-apk**.

## Demo / evaluation ideas
- Walk in front of the home phone → measure alert latency at the owner phone.
- Test from different networks (Wi-Fi vs mobile data) and different cities.
- Metrics: detection latency, false-alarm rate vs motion threshold, snapshot delivery time.

Authors: Abasiekeme Idio, Enobong Etteh
