# RideLink Motorcycle Intercom

## 1. Project Overview

RideLink is a smartphone-based motorcycle intercom and ride-intelligence system designed for reliable communication and safety for riders. It bypasses the need for expensive, dedicated hardware by leveraging the powerful capabilities of modern smartphones.

**The problem we are solving:**

Motorcycle intercom systems are often expensive, proprietary, and lack the flexibility of software-based solutions. RideLink aims to provide a robust, feature-rich, and affordable alternative using the devices riders already own. It addresses the core needs of rider-to-pillion and rider-to-rider communication while adding a layer of intelligence for enhanced safety and awareness.

## 2. System Architecture (LOCKED)

The system is built on a multi-layered communication strategy to ensure connectivity across different scenarios. Each layer has a specific purpose and priority, ensuring that the most critical links are always favored.

### 2.1. `Prime Link v2` (Rider ↔ Pillion)

*   **Technology:** Bluetooth
*   **Purpose:** Provides the highest-priority, lowest-latency audio link between a rider and their pillion passenger. This is the core communication channel.
*   **Key Features:**
    *   **Soft Hold & Call-Safe:** Intelligently manages audio focus to avoid interruption by phone calls or other system sounds.
    *   **Origin-Aware:** Packets are tagged to ensure they are handled correctly within the system.
    *   **Hardware Boundary:** Respects the limitations of standard Bluetooth headsets.

### 2.2. `Chain Link v3` (Rider ↔ Rider)

*   **Technology:** Wi-Fi Direct (Hotspot)
*   **Purpose:** Enables offline, rider-to-rider communication for a group of motorcyclists. It creates a private, ad-hoc network without needing an internet connection.
*   **Key Features:**
    *   **Party-Based Linear Relay:** Forms a "chain" of riders, where each rider relays audio to the next, creating a simple, robust party line.
    *   **Leader Auto-Switch:** If the group leader drops out, another rider in the party automatically takes over leadership to maintain the network.
    *   **No Party Restructuring:** The group structure is locked during a ride to prevent instability.

### 2.3. `Internet Link v2` (Cloud Fallback)

*   **Technology:** Cellular Data (Node.js backend)
*   **Purpose:** Acts as a silent, fallback communication backbone when riders are too far apart for `Chain Link` to function.
*   **Key Features:**
    *   **Leader-to-Leader Relay:** Only group leaders connect to the internet to relay audio, minimizing data usage.
    *   **Silent & Automatic:** The system switches to this mode without any manual intervention.
    *   **Never Primary:** This link is never the primary means of communication.

### 2.4. `INTE LOCK v0` (Intelligence Layer)

*   **Technology:** GPS, Motion Sensors, Machine Learning
*   **Purpose:** Provides a layer of situational awareness and safety alerts.
*   **Key Features:**
    *   **Crash Detection:** Uses sensor data to detect potential accidents.
    *   **Drift Alerts:** Notifies riders if they, their party, or the leader are drifting apart geographically.
    *   **Advisory Only:** The ML model provides suggestions and alerts but never takes control of the system.

## 3. Codebase Documentation

This section details the core components of the Android application and the logic behind them.

### 3.1. `AudioService.kt`

*   **Purpose:** This is the central hub of the Android application. It runs as a foreground service to ensure the intercom remains active even when the app is in the background.
*   **How it Works:**
    *   It initializes and manages the `PrimeLinkManager` and `ChainLinkManager`.
    *   It wires together all the communication callbacks, routing incoming audio from the link managers to the `AudioLoopback` for playback.
    *   It receives captured microphone data from `AudioLoopback` and forwards it to the appropriate link manager for transmission.
    *   It manages the overall state of the system, such as starting and stopping audio capture based on whether any peers are connected.
*   **Logic:** The service uses a `Binder` to allow the UI (e.g., `MainActivity`) to interact with it, start/stop links, and receive status updates. It is responsible for the high-level logic of when to activate the audio hardware.

### 3.2. `AudioLoopback.kt`

*   **Purpose:** This class is the heart of the "Audio Core." It is solely responsible for managing the device's microphone and speaker hardware.
*   **How it Works:**
    *   It uses Android's `AudioRecord` to capture raw audio from the microphone at a low-latency sample rate (8000Hz).
    *   It uses `AudioTrack` to play back incoming audio from other riders.
    *   It includes critical fixes for real-world audio problems, such as a larger `AudioTrack` buffer for Bluetooth devices to prevent the "crackling" sound caused by buffer underruns.
    *   It employs built-in hardware features like `AcousticEchoCanceler` and `AutomaticGainControl` for cleaner audio.
*   **Logic:** The class runs two high-priority threads: one for capturing audio and one for playing it back. It uses a `ReorderBuffer` to handle out-of-order network packets, ensuring smooth playback.

### 3.3. `PrimeLinkManager.kt`

*   **Purpose:** Manages the high-priority `Prime Link` (rider-to-pillion) connection over Bluetooth.
*   **How it Works:**
    *   Uses Bluetooth `BluetoothServerSocket` to "host" a connection for a pillion to join.
    *   Uses a standard `BluetoothSocket` to connect to a host.
    *   Once connected, it creates a dedicated `ConnectedThread` to handle the reading and writing of audio data over the socket.
*   **Logic:** The class is designed for robustness. It includes a reconnection mechanism (`handleDisconnection`) to automatically re-establish a link if it drops. It communicates its status (`HOSTING`, `CONNECTED`, `FAILED`) back to the `AudioService`.

### 3.4. `ChainLinkManager.kt`

*   **Purpose:** Manages the multi-rider `Chain Link` using Wi-Fi Direct.
*   **How it Works:**
    *   It leverages the `WifiP2pManager` API to discover nearby riders, create groups, and connect to them.
    *   Once a Wi-Fi Direct group is formed, it establishes a UDP socket-based communication channel.
    *   The group "leader" (Group Owner) acts as a relay, receiving audio packets from members and broadcasting them back to everyone else. Non-leader members send their audio only to the leader.
*   **Logic:**
    *   **Member Tracking:** A critical feature is the `activeMembers` map. The manager keeps track of who is in the group by monitoring incoming packets. If a member hasn't been heard from in a few seconds, they are "pruned," and the member count is updated. This allows the `AudioService` to know when to start or stop audio capture.
    *   **Echo Protection:** It ensures a device doesn't play back its own audio packets by checking the `originId` on every incoming `LinkPacket`.

### 3.5. `ChainLinkReceiver.kt`

*   **Purpose:** A simple but essential `BroadcastReceiver` that listens for system-level Wi-Fi Direct events.
*   **How it Works:** When the Android system detects changes in Wi-Fi P2P state (e.g., peers found, connection established), this receiver catches the broadcast `Intent` and forwards the relevant information to the `ChainLinkManager` for processing.

### 3.6. `LinkPacket.kt`

*   **Purpose:** This `data class` defines the fundamental data structure for all audio packets sent over the network.
*   **How it Works:** It's a standardized envelope that contains not just the audio `payload`, but also critical metadata:
    *   `originId`: Uniquely identifies the rider who first sent the audio.
    *   `partyId`: Identifies the communication group.
    *   `linkType`: Specifies whether this is a `Prime` or `Chain` link packet.
    *   `sequence`: A number that increments with each packet, used by the `ReorderBuffer` to put audio back in the correct order.
    *   `hopCount`: Used in relay logic to track how many times a packet has been forwarded.

### 3.7. `ReorderBuffer.kt`

*   **Purpose:** Solves a fundamental problem with network audio: packets often arrive out of order (jitter). This class's job is to reassemble the audio stream correctly.
*   **How it Works:**
    *   It uses a `PriorityQueue` to automatically sort incoming packets based on their `sequence` number.
    *   It implements an **adaptive pre-fill** mechanism. It waits for a small number of packets to arrive to gauge network jitter and dynamically determines how much audio to buffer before starting playback. This provides a balance between low latency and smooth audio.
*   **Logic:** This class contains intelligent logic to handle real-world network conditions. It dynamically adjusts its buffering strategy and includes logic to handle clock drift (when one device sends audio slightly faster than another receives it), preventing buffer overflows and audio glitches.