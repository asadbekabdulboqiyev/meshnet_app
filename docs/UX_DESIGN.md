# MeshNet — UI/UX Design System

> **Version:** 1.0  
> **Date:** 2026-08-08  
> **Target:** Android (Flutter)  
> **Language:** English (en)  
> **Author:** TechCorp — Design Team

---

## Table of Contents

1. [Product Character](#1-product-character)
2. [Color Palette](#2-color-palette)
3. [Typography](#3-typography)
4. [Screens — Screen-by-Screen](#4-screens)
5. [Component Library](#5-component-library)
6. [Status Icons (State Icons)](#6-status-icons)
7. [Micro-Interactions and UX Motion](#7-micro-interactions)
8. [Empty States](#8-empty-states)
9. [Responsive Rules](#9-responsive-rules)
10. [Accessibility (WCAG 2.1 AA)](#10-accessibility)
11. [Design Tokens (JSON)](#11-design-tokens)
12. [Frontend Handoff — Flutter Specification](#12-handoff)
13. [QA Checklist](#13-qa-checklist)

---

## 1. Product Character

### 1.1 What is this?

MeshNet — an app for communicating over an **offline P2P mesh network** in
natural disasters, internet blackouts, and oppressive situations. No internet
required. Devices connect to each other via Bluetooth, Wi-Fi Direct, and other
local protocols.

### 1.2 User Profile

| Feature | Description |
|-----------|--------|
| **Age** | 18–55 |
| **State** | Emergency, under stress, needs a quick solution |
| **Technical level** | Average — ordinary user |
| **Environment** | Street, building, underground, rain, darkness of night |

### 1.3 Design Principles

| Principle | Notes |
|---------|------|
| **Simple** | Minimal elements, maximum meaning. Understood in 3 seconds under stress |
| **Reliable** | The result of an action is clearly visible. The user must feel that the message has been delivered |
| **Fast** | Zero-friction. Each screen stays within 2-3 steps |
| **Offline-first** | The app works fully even with no network; only the mesh connection waits |
| **Secure** | Encrypted. The banner is always visible. The user stays confident |

### 1.4 Emotional Design

- **Under stress** — soothing colors (teal), but clear warnings (orange)
- **Feeling of isolation** — the mesh network visualization conveys "you are not alone"
- **Confidence** — the encrypted banner, delivery status, and security indicators are always visible

---

## 2. Color Palette

### 2.1 Primary Palette (Dark Mode — Primary)

| Token | Color | HEX | RGB | RGBA | Role |
|-------|------|-----|-----|------|-----------|
| `primary-900` | Deep Teal | `#0D3B3E` | 13, 59, 62 | rgba(13,59,62,1) | Background, header |
| `primary-700` | Teal | `#1A6B6F` | 26, 107, 111 | rgba(26,107,111,1) | Primary buttons, active state |
| `primary-500` | Medium Teal | `#2A8F94` | 42, 143, 148 | rgba(42,143,148,1) | Links, secondary elements |
| `primary-300` | Light Teal | `#5CC4C8` | 92, 196, 200 | rgba(92,196,200,1) | Hover, border, divider |
| `primary-100` | Pale Teal | `#D4F0F1` | 212, 240, 241 | rgba(212,240,241,1) | Badge background, tooltip |

### 2.2 Signal Colors (Accent)

| Token | Color | HEX | Role |
|-------|------|-----|-----------|
| `accent-500` | Signal Orange | `#FF6B35` | Warnings, live badge, emergency button |
| `accent-400` | Light Orange | `#FF8C5A` | Hover state |
| `accent-600` | Dark Orange | `#E55A2B` | Pressed state |

### 2.3 Coverage Color (Green)

| Token | Color | HEX | Role |
|-------|------|-----|-----------|
| `success-500` | Signal Green | `#22C55E` | Online, delivered, coverage zone |
| `success-400` | Light Green | `#4ADE80` | Hover, badge |
| `success-600` | Dark Green | `#16A34A` | Pressed state |

### 2.4 Status Colors

| Token | Color | HEX | Role |
|-------|------|-----|-----------|
| `error-500` | Red | `#EF4444` | Error, not delivered, danger |
| `error-400` | Light Red | `#F87171` | Error badge |
| `warning-500` | Amber | `#F59E0B` | Warning, weak signal |
| `warning-400` | Light Amber | `#FBBF24` | Warning badge |
| `info-500` | Blue | `#3B82F6` | Information, routing status |
| `info-400` | Light Blue | `#60A5FA` | Info badge |

### 2.5 Neutral Colors

| Token | Color | HEX | Role |
|-------|------|-----|-----------|
| `neutral-950` | Near Black | `#0A0A0A` | Text (dark mode) |
| `neutral-900` | Dark | `#171717` | Primary text |
| `neutral-800` | | `#262626` | Card background (dark mode) |
| `neutral-700` | | `#404040` | Secondary text |
| `neutral-500` | | `#737373` | Placeholder, disabled |
| `neutral-400` | | `#A3A3A3` | Border, divider |
| `neutral-300` | | `#D4D4D4` | Light border |
| `neutral-200` | | `#E5E5E5` | Light card bg |
| `neutral-100` | | `#F5F5F5` | Off-white background (light mode) |
| `neutral-50` | Off-White | `#FAFAFA` | Bottom sheet |

### 2.6 Theme Switching

```
Dark Mode (primary):  Background #0D3B3E → Card #1A2A2B → Text #F5F5F5
Light Mode (secondary): Background #FAFAFA → Card #FFFFFF → Text #0A0A0A
```

**Default theme:** dark mode. The user can switch it in settings.

---

## 3. Typography

### 3.1 Fonts

| Font | Type | Google Fonts | Usage |
|--------|------|--------------|---------------|
| **Inter** | Sans-serif | `fonts.google.com/specimen/Inter` | UI text, buttons, headings |
| **JetBrains Mono** | Monospace | `fonts.google.com/specimen/JetBrains+Mono` | IP addresses, hashes, MAC, technical text |
| **Roboto Mono** | Monospace | `fonts.google.com/specimen/Roboto+Mono` | Alternative (if JetBrains Mono is not a fit) |

### 3.2 Typographic Hierarchy

| Value | Dark Mode | Light Mode | Weight | Size | Line Height | Tracking |
|--------|-----------|------------|--------|------|-------------|----------|
| `display-large` | `#F5F5F5` | `#0A0A0A` | 700 (Bold) | 32sp | 40sp | -0.5 |
| `display-medium` | `#F5F5F5` | `#0A0A0A` | 700 (Bold) | 28sp | 36sp | -0.25 |
| `display-small` | `#F5F5F5` | `#0A0A0A` | 600 (SemiBold) | 24sp | 32sp | 0 |
| `headline-large` | `#F5F5F5` | `#0A0A0A` | 600 (SemiBold) | 22sp | 28sp | 0 |
| `headline-medium` | `#F5F5F5` | `#0A0A0A` | 600 (SemiBold) | 20sp | 28sp | 0 |
| `headline-small` | `#F5F5F5` | `#0A0A0A` | 600 (SemiBold) | 18sp | 24sp | 0 |
| `title-large` | `#F5F5F5` | `#0A0A0A` | 600 (SemiBold) | 16sp | 24sp | 0.15 |
| `title-medium` | `#F5F5F5` | `#0A0A0A` | 500 (Medium) | 14sp | 20sp | 0.1 |
| `title-small` | `#F5F5F5` | `#0A0A0A` | 500 (Medium) | 12sp | 16sp | 0.1 |
| `body-large` | `#E5E5E5` | `#171717` | 400 (Regular) | 16sp | 24sp | 0.5 |
| `body-medium` | `#D4D4D4` | `#262626` | 400 (Regular) | 14sp | 20sp | 0.25 |
| `body-small` | `#A3A3A3` | `#404040` | 400 (Regular) | 12sp | 16sp | 0.4 |
| `label-large` | `#F5F5F5` | `#0A0A0A` | 500 (Medium) | 14sp | 20sp | 0.1 |
| `label-medium` | `#D4D4D4` | `#171717` | 500 (Medium) | 12sp | 16sp | 0.5 |
| `label-small` | `#A3A3A3` | `#404040` | 500 (Medium) | 10sp | 12sp | 0.5 |
| `code` | `#5CC4C8` | `#1A6B6F` | 400 (Regular) | 13sp | 20sp | 0 |

### 3.3 Rules

- **Maximum line length:** 60 characters (chat messages — 45 characters)
- **Spacing:** 8sp between heading and text, 4sp between text and text
- **Contrast ratio:** WCAG AA — 4.5:1 (body), 3:1 (large text)
- **Monospace:** only for technical data (IP, MAC, hash). Do not use in regular text

---

## 4. Screens — Screen-by-Screen

### 4.1 Home / Network Screen

**Goal:** the user must understand the mesh network status in 1 second.

#### Layout (top → bottom):

```
┌─────────────────────────────────────┐
│  🟢 MeshNet              ⚙️       │  ← Top App Bar
│  Network: Active | 3 connections   │
├─────────────────────────────────────┤
│                                     │
│      ╭───╮     ╭───╮               │
│      │ 📱│─ ─ ─│ 📱│               │  ← Mesh Visual
│      ╰─┬─╯     ╰─┬─╯               │     (cloud-like
│        │    📱    │                 │      hubs,
│        │─ ─ │ ─ ─│                 │      connecting lines)
│        │    │     │                 │
│      ╭─┴─╮       ╰─╮               │
│      │ 📱│    ╭───╮ │               │
│      ╰───╯    │ 📱│ │               │
│               ╰───╯ │               │
│                                     │
│  👤 You (self): #A7F3D0            │  ← Self marker
│  📍 0.3 km radius                  │     (green circle)
├─────────────────────────────────────┤
│  📡 Network Info                    │
│  ├─ Active peers: 3                │
│  ├─ Routing hops: 2                │
│  ├─ Signal strength: ⚡ High        │
│  └─ Last update: 2 seconds         │
├─────────────────────────────────────┤
│  [ 📡 Share ]  [ 💬 Message ]      │  ← Primary Actions
├─────────────────────────────────────┤
│  🏠    📋    💬    ⚙️              │  ← Bottom Navigation
│  Network Contacts Messages Settings │
└─────────────────────────────────────┘
```

#### Component details:

| Element | Description | Animation |
|---------|--------|------------|
| **Mesh Visual** | Rounded nodes + dashed lines | Pulse (every 3 seconds) |
| **Node colors** | Self = green, online = teal, offline = gray | Fade in/out |
| **Line** | Active link = solid teal, weak = dashed amber, none = gray | Stroke animation |
| **"You" marker** | Green circle + label | Gentle glow |
| **Network info** | Inside a card, open state | Slide up |

#### Interactivity:

- Tap a node → brief profile (name + signal)
- Pinch-to-zoom on the mesh visual
- Pull-to-refresh → rescan the network

---

### 4.2 Contacts List

**Goal:** determine offline/online status at a glance.

#### Layout:

```
┌─────────────────────────────────────┐
│  📋 Contacts            🔍  ➕     │  ← Top Bar
├─────────────────────────────────────┤
│  Search...                         │  ← Search bar
├─────────────────────────────────────┤
│  🟢 ONLINE (2)                     │  ← Section header
├─────────────────────────────────────┤
│  ┌─────────────────────────────┐   │
│  │ 🟢  Karimov Bobur          │   │  ← Online contact
│  │     📡 0.1 km | Signal ⚡   │   │     (teal left border)
│  │     [ 💬 Write ] [ 📞 ]    │   │
│  └─────────────────────────────┘   │
│  ┌─────────────────────────────┐   │
│  │ 🟢  Raximova Nilufar       │   │
│  │     📡 0.3 km | Signal ⚡   │   │
│  │     [ 💬 Write ] [ 📞 ]    │   │
│  └─────────────────────────────┘   │
├─────────────────────────────────────┤
│  ⚫ OFFLINE (5)                    │  ← Section header
├─────────────────────────────────────┤
│  ┌─────────────────────────────┐   │
│  │ ⚫  Toshmatov Sardor       │   │  ← Offline contact
│  │     📡 | Last seen: 15 min  │   │     (gray left border)
│  │     [ 📞 Call ]            │   │
│  └─────────────────────────────┘   │
│  ┌─────────────────────────────┐   │
│  │ 🔵  Valiyev Jamshid        │   │  ← Routing (signal relay)
│  │     📡 0.5 km | Signal 💪   │   │     (blue left border)
│  │     [ 💬 Write ] [ 📞 ]    │   │
│  └─────────────────────────────┘   │
│  ...                               │
├─────────────────────────────────────┤
│  🏠    📋    💬    ⚙️              │
└─────────────────────────────────────┘
```

#### Contact Card Components:

| Element | Description |
|---------|--------|
| **Avatar** | 44dp rounded, with the default initial |
| **Status dot** | 12dp, overlaid on the left. Green=online, Gray=offline, Blue=routing |
| **Name** | `title-medium`, 14sp |
| **Distance/Signal** | `body-small`, 12sp. "Unknown" if no distance |
| **Action buttons** | 40dp height, icon + text. Primary = teal, Ghost = outline |

#### Interactivity:

- Swipe left → delete/hide
- Long press → detailed profile
- Each contact carries an offline/online banner

---

### 4.3 Chat Screen

**Goal:** encrypted messaging. Message delivery must be clearly visible.

#### Layout:

```
┌─────────────────────────────────────┐
│  ←  Karimov Bobur     🟢 Online   │  ← Header
│     📡 0.1 km | Signal ⚡          │
├─────────────────────────────────────┤
│  🔒 This chat is E2E encrypted     │  ← Encryption Banner
│  │  Messages are only visible to   │     (pastel teal bg,
│  │  you and the recipient          │      teal text)
│  🔒                                │
├─────────────────────────────────────┤
│                                     │
│  14:32                             │  ← Timestamp
│  ┌─────────────────────────────┐   │
│  │ Salam! Where are you?      │   │  ← incoming (teal bg,
│  └─────────────────────────────┘   │     white text)
│              14:33                 │
│        ┌───────────────────────┐   │
│        │ Wa alaikum assalam!   │   │  ← outgoing (primary-700 bg,
│        │ I'm on Central street │   │     white text)
│        └───────────────────────┘   │
│                        ✓✓          │  ← delivered (teal)
│                                     │
│  14:35                             │
│  ┌─────────────────────────────┐   │
│  │ 🗺️ Location: Central       │   │  ← location card
│  │    street, house 45         │   │
│  │    [ 📍 View on map ]       │   │
│  └─────────────────────────────┘   │
│                                     │
│  14:36                             │
│        ┌───────────────────────┐   │
│        │ Did the message       │   │
│        │ arrive?               │   │
│        └───────────────────────┘   │
│                     ⏳              │  ← pending (gray, pulsing)
│                                     │
├─────────────────────────────────────┤
│  ┌─────────────────────────┐  📎  │  ← Input bar
│  │ Write a message...      │  🎤  │
│  └─────────────────────────┘  ➤   │
└─────────────────────────────────────┘
```

#### Message Statuses:

| Status | Indicator | Description |
|--------|-------|--------|
| **Pending** | ⏳ (pulsing) | Sending. Searching the mesh network |
| **Sent** | ✓ (gray) | Reached a nearby node |
| **Delivered** | ✓✓ (teal) | Reached the recipient |
| **Read** | ✓✓ (green) | Read |
| **Failed** | ⚠️ (red) | Not delivered. Resend button |
| **Routing** | 🔄 (blue) | Being routed through another node |

#### Encryption Banner:

- Always visible at the top of the chat
- Background: `primary-100` (10% opacity) — dark mode: `#1A2A2B`
- Text: `primary-500`
- Icon: 🔒 or an SVG lock icon
- Not dismissible — always visible

#### Interactivity:

- Send button pressed → swipe animation on the button icon (pencil line → paper plane)
- Message long press → Reply, Copy, Delete
- Swipe down → timestamps appear
- Typing indicator → 3-dot pulsation

---

### 4.4 Pairing Screen

**Goal:** connect devices via QR. Simple and fast.

#### 4.4.1 Show QR (add your device to another device)

```
┌─────────────────────────────────────┐
│  ←  Pairing            ❌          │
├─────────────────────────────────────┤
│                                     │
│  📱 Show your device to other      │
│  devices                           │
│                                     │
│  ┌─────────────────────────────┐   │
│  │                             │   │
│  │    ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄    │   │  ← QR Code (256x256)
│  │    █ █▀▀▄ █▀▄▀█ █▀▄▀█ █    │   │     Centered, large
│  │    █ █▀▀▄ █▄▀▄█ █▄▀▄█ █    │   │
│  │    ▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀    │   │
│  │                             │   │
│  │    MeshNet Pairing QR       │   │
│  └─────────────────────────────┘   │
│                                     │
│  📡 Signal: ⚡ Strong              │
│  ⏱️ Expires: 5:00 minutes          │
│  🔒 Single-use only                │
│                                     │
│  [ 🔄 Refresh ]                    │  ← QR refresh
│                                     │
├─────────────────────────────────────┤
│  💡 Tip:                           │
│  • Show the QR to the other device │
│  • Make sure Bluetooth is on       │
│  • Stand within 2 meters          │
└─────────────────────────────────────┘
```

#### 4.4.2 Scan QR (add another device)

```
┌─────────────────────────────────────┐
│  ←  Pairing            ❌          │
├─────────────────────────────────────┤
│                                     │
│  📷 Scan a QR code                 │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ ┌───────────────────────┐   │   │  ← Camera viewfinder
│  │ │                       │   │   │     (corners = teal)
│  │ │    ┌───────────┐      │   │   │
│  │ │    │           │      │   │   │  ← Scanning frame
│  │ │    │  📷       │      │   │   │     (animated corners)
│  │ │    │           │      │   │   │
│  │ │    └───────────┘      │   │   │
│  │ │                       │   │   │
│  │ └───────────────────────┘   │   │
│  └─────────────────────────────┘   │
│                                     │
│  📍 Center the QR code             │
│                                     │
│  ⏳ Waiting...                     │  ← Scanning indicator
│                                     │
│  [ 💡 Manual entry ]               │  ← Manual fallback
└─────────────────────────────────────┘
```

#### 4.4.3 Pairing Result

```
┌─────────────────────────────────────┐
│                                     │
│         ✅                          │  ← Success state
│                                     │
│    Paired successfully!            │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ 📱 Karimov Bobur's Phone   │   │  ← Paired device card
│  │ 📡 Signal: ⚡ High          │   │
│  │ 🔒 Encrypted: Yes          │   │
│  │ ⏱️ Connected: 14:35        │   │
│  └─────────────────────────────┘   │
│                                     │
│  [ ✅ Got it, continue ]           │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ ⚠️ An error occurred!       │   │  ← Error state
│  │                              │   │
│  │ Reason: Bluetooth is off    │   │
│  │ [ 🔧 Turn on Bluetooth ]    │   │
│  │ [ 🔄 Retry ]                │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ ⏱️ Time expired!            │   │  ← Timeout state
│  │                              │   │
│  │ The QR code has expired     │   │
│  │ [ 🔄 Generate new QR ]      │   │
│  └─────────────────────────────┘   │
│                                     │
└─────────────────────────────────────┘
```

---

### 4.5 Settings Screen

```
┌─────────────────────────────────────┐
│  ⚙️  Settings                       │
├─────────────────────────────────────┤
│                                     │
│  👤 PROFILE                         │
│  ├─ Name: Karimov Bobur            │
│  ├─ Status: Need help              │
│  └─ Avatar: [📷 Change]            │
│                                     │
│  📡 NETWORK                         │
│  ├─ Auto-start: [🟢 ON]            │
│  │  (Auto-connect when the app opens)│
│  ├─ Transport priority:            │
│  │  1. Bluetooth ← drag to reorder  │
│  │  2. Wi-Fi Direct                 │
│  │  3. Hotspot                      │
│  ├─ Mesh radius: ◀━●━━▶ 0.5 km    │
│  └─ Max peers: [5]                 │
│                                     │
│  🔒 SECURITY                        │
│  ├─ Encryption: E2E (Always On)     │
│  ├─ Key management: [Device-local]  │
│  └─ Auto-delete: [7 days]          │
│                                     │
│  🔔 NOTIFICATIONS                   │
│  ├─ Message sound: [🟢 ON]         │
│  ├─ Vibration: [🟢 ON]             │
│  └─ Emergency only: [⚫ OFF]        │
│                                     │
│  🎨 INTERFACE                       │
│  ├─ Theme: [🌙 Dark ○ Light]       │
│  ├─ Large text: [⚫ OFF]            │
│  └─ Language: [English 🇬🇧]         │
│                                     │
│  ℹ️  ABOUT                          │
│  ├─ Version: 1.0.0                 │
│  ├─ Licenses                       │
│  └─ Privacy Policy                 │
│                                     │
└─────────────────────────────────────┘
```

#### Transport Priority — Drag & Drop:

- Drag each transport row to reorder
- While dragging: 4dp elevation, subtle shadow
- On drop: bounce animation (150ms)
- Active transport: teal dot indicator

---

### 4.6 Onboarding (3 Steps)

#### Step 1: "What is this?"

```
┌─────────────────────────────────────┐
│                                     │
│         🌐                          │  ← Hero illustration
│       / | \                         │     (mesh network
│      📱─📱─📱                      │      animated Lottie)
│                                     │
│  MeshNet — Your own                 │
│  personal mesh network              │
│                                     │
│  No internet needed. Communicate    │
│  directly with nearby devices       │
│  via Bluetooth and Wi-Fi Direct.   │
│                                     │
│  Built for natural disasters,       │
│  blackouts and emergencies.        │
│                                     │
│              ● ○ ○                  │  ← Page indicator
│                                     │
│  [ Next ➤ ]                        │
│  [ Skip ]                          │
└─────────────────────────────────────┘
```

#### Step 2: "How does it work?"

```
┌─────────────────────────────────────┐
│                                     │
│         📱                          │  ← Hero illustration
│        ↕️                           │     (devices pairing
│         📱                          │      animation)
│                                     │
│  1️⃣ Open the app                    │
│  2️⃣ Scan the QR code               │
│  3️⃣ Connect automatically          │
│                                     │
│  ┌─────────────────────────────┐   │
│  │  ✅ Bluetooth required      │   │  ← Requirements list
│  │  ✅ Wi-Fi Direct required   │   │
│  │  ✅ Bluetooth permission    │   │
│  └─────────────────────────────┘   │
│                                     │
│              ○ ● ○                  │
│                                     │
│  [ Next ➤ ]                        │
│  [ Back ]                          │
└─────────────────────────────────────┘
```

#### Step 3: "Ready!"

```
┌─────────────────────────────────────┐
│                                     │
│         🚀                          │  ← Hero illustration
│       / | \                         │     (success/checkmark
│      📱─📱─📱                      │      animation)
│                                     │
│  Everything's ready!                │
│                                     │
│  Now you can communicate with       │
│  those around you even offline.    │
│                                     │
│  💡 Tip:                           │
│  The first step — pair with         │
│  someone near you!                 │
│                                     │
│              ○ ○ ●                  │
│                                     │
│  [ 🚀 Start ]                      │
│  [ Back ]                          │
└─────────────────────────────────────┘
```

#### Onboarding Animations:

- Page transition → horizontal page transition (300ms, ease-out)
- Lottie animations → loop, 2s cycle
- "Start" button → scale-up bounce (0.9 → 1.05 → 1.0, 400ms)

---

## 5. Component Library

### 5.1 Card

#### Variants:

| Variant | Description | Use Case |
|---------|--------|----------|
| `card-default` | Neutral-800 background, 12dp radius, 1dp border | Regular content |
| `card-elevated` | Neutral-800 + 2dp elevation | Primary content, tappable |
| `card-outlined` | Transparent background, 1dp border | Secondary |
| `card-success` | Success-500 left border | Success state |
| `card-warning` | Warning-500 left border | Warning |
| `card-error` | Error-500 left border | Error |

#### Specification:

```
Padding: 16dp (horizontal), 16dp (vertical)
Border radius: 12dp
Border: 1dp solid neutral-700 (dark), neutral-300 (light)
Elevation: 0dp (default), 2dp (elevated)
Shadow: rgba(0,0,0,0.15) 0px 2px 8px (elevated only)
```

### 5.2 Buttons

#### 5.2.1 Primary Button

```
Background: primary-700 (#1A6B6F)
Text: White (#FFFFFF)
Height: 48dp
Border radius: 12dp
Padding: 16dp horizontal, 12dp vertical
Font: Inter SemiBold 14sp
Icon: 20dp, left-aligned
```

**States:**
- Default → primary-700
- Hover/Focus → primary-500
- Pressed → primary-900
- Disabled → neutral-700, text neutral-500

**Animation:** scale(0.97) on press, 100ms

#### 5.2.2 Ghost Button

```
Background: transparent
Border: 1.5dp primary-500
Text: primary-500
Height: 48dp
Border radius: 12dp
```

#### 5.2.3 Danger Button

```
Background: error-500 (#EF4444)
Text: White
Height: 48dp
Border radius: 12dp
```

#### 5.2.4 Icon Button

```
Size: 44dp x 44dp
Border radius: 22dp (circular)
Background: transparent / neutral-800
Icon: 24dp
Touch target: 48dp minimum
```

#### 5.2.5 FAB (Floating Action Button)

```
Size: 56dp x 56dp
Border radius: 16dp
Background: primary-700
Icon: 24dp, white
Elevation: 4dp
Position: bottom-right, 16dp from edges
Shadow: rgba(0,0,0,0.25) 0px 4px 12px
```

### 5.3 Badge

| Variant | Color | Description |
|---------|------|--------|
| `badge-online` | success-500 | User is online |
| `badge-offline` | neutral-500 | Offline |
| `badge-routing` | info-500 | Routing/signaling |
| `badge-warning` | warning-500 | Weak signal |
| `badge-error` | error-500 | Error |
| `badge-live` | accent-500 | Live/now (pulsing) |
| `badge-encrypted` | primary-500 | Encrypted |

#### Specification:

```
Height: 24dp
Border radius: 12dp
Padding: 8dp horizontal
Font: Inter Medium 10sp
Min width: 48dp
```

### 5.4 Input Field

```
Height: 56dp
Border: 1.5dp neutral-500
Border radius: 12dp
Background: neutral-800 (dark), white (light)
Padding: 16dp
Font: Inter Regular 16sp
Placeholder: neutral-500
Focus border: primary-500
Error border: error-500
```

### 5.5 Toggle

```
Track: 52dp x 32dp, border radius 16dp
Thumb: 24dp circle
OFF: Track neutral-700, thumb neutral-400
ON: Track primary-500, thumb white
Animation: 200ms ease-out
```

### 5.6 Slider

```
Track height: 4dp
Active track: primary-500
Inactive track: neutral-700
Thumb: 20dp circle, white, 2dp border primary-500
```

### 5.7 Progress Indicator

```
Linear: 4dp height, primary-500
Circular: 40dp diameter, 3dp stroke, primary-500
Animation: continuous rotation, 1000ms
```

### 5.8 Snackbar / Toast

```
Background: neutral-800
Text: white
Border radius: 8dp
Padding: 12dp horizontal, 16dp vertical
Duration: 3 seconds
Position: bottom, 16dp from bottom edge
Max width: 400dp
```

### 5.9 Bottom Sheet

```
Background: neutral-900 (dark), neutral-50 (light)
Border radius: 20dp (top only)
Handle: 32dp x 4dp, neutral-700, centered
Draggable: yes
Backdrop: rgba(0,0,0,0.5)
```

### 5.10 Dialog

```
Background: neutral-800
Border radius: 16dp
Padding: 24dp
Max width: 320dp
Title: headline-medium, neutral-50
Body: body-large, neutral-300
Actions: right-aligned, ghost buttons
```

---

## 6. Status Icons (State Icons)

### 6.1 Network States

| State | Icon | Color | Text | Description |
|-------|------|------|------|--------|
| `no_network` | 📡❌ | neutral-500 | "Network not found" | Bluetooth/Wi-Fi off |
| `no_peers` | 👥❌ | neutral-500 | "No one nearby" | No peers around |
| `peer_detected` | 👥✅ | success-500 | "3 devices found" | Peers detected |
| `connected` | 🟢 | success-500 | "Connected to network" | Full connection |
| `disconnected` | ⚫ | neutral-500 | "Connection lost" | Link dropped |
| `connecting` | 🔄 | info-500 | "Connecting..." | In progress |
| `weak_signal` | ⚡⚠️ | warning-500 | "Weak signal" | Low quality |

### 6.2 Message States

| State | Icon | Color | Description |
|-------|------|------|--------|
| `sending` | ⏳ (pulsing) | neutral-500 | Sending |
| `sent` | ✓ | neutral-400 | Sent |
| `delivered` | ✓✓ | primary-500 | Delivered |
| `read` | ✓✓ | success-500 | Read |
| `failed` | ⚠️ | error-500 | Not delivered |

### 6.3 Connection Quality

| Level | Icon | Color | Description |
|-------|------|------|--------|
| Excellent | ⚡⚡⚡ | success-500 | Strong signal |
| Good | ⚡⚡ | success-400 | Good |
| Fair | ⚡ | warning-500 | Average |
| Poor | ⚡⚠️ | error-500 | Weak |

---

## 7. Micro-Interactions

### 7.1 Message Send Button

**Animation sequence:**

1. **Tap** → Button scale(0.95), 80ms
2. **Input clear** → Text fade out, 120ms
3. **Icon transition** → Keyboard icon → Paper plane icon, rotation(360°), 250ms
4. **Send** → Paper plane → flies up (translateY -20dp), 300ms, ease-in
5. **Message appear** → Slide-up + fade-in inside the chat, 200ms
6. **Status update** → Pending (⏳ pulsing) → Sent (✓) → Delivered (✓✓), sequential

### 7.2 Bluetooth Pairing Choreography

**Pairing process:**

1. **QR scan** → Corner brackets animate (scale 1.0 → 1.1 → 1.0, pulse)
2. **Device found** → Vibration (100ms), sound tone (optional)
3. **Connecting** → Both device icons orbit each other, 2s loop
4. **Connected** → Both icons settle, green ring expands + fades, 400ms
5. **Encryption established** → Lock icon appears with a scale bounce, 300ms

### 7.3 Mesh Node Animations

| State | Animation |
|-------|------------|
| **Node join** | Fade-in + scale(0.5 → 1.0), 300ms |
| **Node leave** | Fade-out + scale(1.0 → 0.5), 300ms |
| **Signal strength** | Node pulse speed = signal strength (strong = fast) |
| **Connection line** | Dashed stroke animation, continuous |
| **Data transfer** | Particles along the connection line, 2s loop |

### 7.4 Navigation Transitions

| Transition | Animation |
|------------|------------|
| **Screen enter** | Slide from right, 300ms, ease-out |
| **Screen exit** | Slide to left, 250ms, ease-in |
| **Tab switch** | Crossfade, 200ms |
| **Bottom sheet open** | Slide up + backdrop fade, 350ms |
| **Dialog open** | Scale(0.9 → 1.0) + fade, 250ms |
| **Card tap** | Scale(1.0 → 0.98), 100ms |

### 7.5 Haptic Feedback

| Location | Haptic |
|-----------|--------|
| **Message received** | Light impact |
| **Message sent** | Success |
| **Error** | Error |
| **QR scan success** | Medium impact |
| **Pairing connected** | Heavy impact |
| **Button press** | Selection |

---

## 8. Empty States

### 8.1 No Network

```
┌─────────────────────────────────────┐
│                                     │
│         📡❌                        │  ← Illustration (48dp)
│                                     │
│    Network not found               │  ← headline-medium
│                                     │
│    Check that Bluetooth and Wi-Fi   │  ← body-large
│    are turned on.                  │
│                                     │
│    [ 🔧 Open settings ]            │  ← Primary button
│                                     │
│    ┌─────────────────────────┐     │
│    │ Bluetooth: [🟢 Turn on] │     │  ← Quick settings
│    │ Wi-Fi:     [🟢 Turn on] │     │
│    └─────────────────────────┘     │
│                                     │
└─────────────────────────────────────┘
```

### 8.2 No Peers

```
┌─────────────────────────────────────┐
│                                     │
│         👥❌                        │
│                                     │
│    No one around                   │
│                                     │
│    To join the mesh, people         │
│    near you also need to be         │
│    using MeshNet.                  │
│                                     │
│    [ 📲 Share with friends ]       │
│    [ 🔄 Rescan ]                   │
│                                     │
└─────────────────────────────────────┘
```

### 8.3 Roaming / No-Coverage Environment

```
┌─────────────────────────────────────┐
│                                     │
│         🗺️⚠️                       │
│                                     │
│    Weak signal                     │
│                                     │
│    Physical obstacles are          │
│    affecting signal quality.       │
│    Try moving to an open area.     │
│                                     │
│    📡 Signal strength: ▓▓░░░ (35%) │
│                                     │
│    [ 🔄 Reconnect ]                │
│                                     │
└─────────────────────────────────────┘
```

### 8.4 Empty Chat

```
┌─────────────────────────────────────┐
│                                     │
│         💬                          │
│                                     │
│    No messages yet                 │
│                                     │
│    Write your first message!       │
│                                     │
│    🔒 Messages are E2E encrypted   │
│                                     │
│  ┌────────────────────────────┐    │
│  │ Write a message...   🎤 ➤ │    │
│  └────────────────────────────┘    │
│                                     │
└─────────────────────────────────────┘
```

### 8.5 Empty Contacts

```
┌─────────────────────────────────────┐
│                                     │
│         📋                          │
│                                     │
│    No contacts                     │
│                                     │
│    Add contacts by pairing with     │
│    other users.                    │
│                                     │
│    [ ➕ Add contact ]              │
│                                     │
└─────────────────────────────────────┘
```

---

## 9. Responsive Rules

### 9.1 Breakpoints

| Device | Width | Columns | Margins |
|--------|-------|---------|---------|
| **Small Phone** | < 360dp | 4 | 16dp |
| **Phone** | 360–599dp | 4 | 16dp |
| **Large Phone** | 600–839dp | 8 | 24dp |
| **Tablet** | 840–1199dp | 12 | 32dp |
| **Large Tablet** | > 1200dp | 12 | Auto center, max 1200dp |

### 9.2 Grid System

- **Column:** 4dp base grid
- **Gutter:** 16dp (phone), 24dp (tablet)
- **Margin:** 16dp (phone), 24dp (large phone), 32dp (tablet)

### 9.3 Component Sizing

| Component | Phone | Tablet |
|-----------|-------|--------|
| **Top App Bar** | 56dp | 64dp |
| **Bottom Nav** | 80dp | 80dp |
| **Card** | Full width - 32dp | Max 400dp, centered |
| **Dialog** | Full width - 48dp | Max 320dp |
| **FAB** | 56dp | 56dp |
| **Button** | Full width (primary), auto (secondary) | Auto width |

### 9.4 Mesh Visual Scaling

| Device | Node size | Line width | Animation speed |
|--------|-----------|------------|-----------------|
| Phone (< 400dp) | 36dp | 1dp | Normal |
| Phone (400-599dp) | 44dp | 1.5dp | Normal |
| Tablet (> 600dp) | 56dp | 2dp | Slow (more detail) |

---

## 10. Accessibility (WCAG 2.1 AA)

### 10.1 Contrast Requirements

| Element | Minimum Ratio | Tool |
|---------|---------------|------|
| **Body text** | 4.5:1 | Chrome DevTools |
| **Large text** (18sp+) | 3:1 | Chrome DevTools |
| **UI components** | 3:1 | axe DevTools |
| **Focus indicators** | 3:1 | Manual |

### 10.2 Touch Targets

- Minimum tap target: **48dp x 48dp**
- Spacing between targets: **8dp minimum**
- Icon buttons: **44dp icon + 4dp padding = 48dp total**

### 10.3 Screen Reader Support

| Element | Label | Hint |
|---------|-------|------|
| **Message input** | "Message text" | "Type and press to send" |
| **Send button** | "Send message" | — |
| **QR Scanner** | "Scan QR code" | "Point the camera at the QR code" |
| **Contact card** | "[Name], [status]" | "Tap to open chat" |
| **Toggle** | "[name], [on/off]" | "Tap to change" |
| **Network status** | "Network status: [status]" | — |

### 10.4 Motion

- Reduce motion: respect `prefers-reduced-motion`
- Alternative: static indicators instead of animations
- Critical animations (sending, connecting) still work but simplified

### 10.5 Color Independence

- Status never relies on color alone
- Always paired with icon + text
- Example: "Online" = green dot + ✓ icon + "Online" text

---

## 11. Design Tokens (JSON)

```json
{
  "meshnet_design_tokens": {
    "version": "1.0.0",
    "colors": {
      "primary": {
        "900": "#0D3B3E",
        "700": "#1A6B6F",
        "500": "#2A8F94",
        "300": "#5CC4C8",
        "100": "#D4F0F1"
      },
      "accent": {
        "600": "#E55A2B",
        "500": "#FF6B35",
        "400": "#FF8C5A"
      },
      "success": {
        "600": "#16A34A",
        "500": "#22C55E",
        "400": "#4ADE80"
      },
      "error": {
        "500": "#EF4444",
        "400": "#F87171"
      },
      "warning": {
        "500": "#F59E0B",
        "400": "#FBBF24"
      },
      "info": {
        "500": "#3B82F6",
        "400": "#60A5FA"
      },
      "neutral": {
        "950": "#0A0A0A",
        "900": "#171717",
        "800": "#262626",
        "700": "#404040",
        "500": "#737373",
        "400": "#A3A3A3",
        "300": "#D4D4D4",
        "200": "#E5E5E5",
        "100": "#F5F5F5",
        "50": "#FAFAFA"
      }
    },
    "typography": {
      "fontFamilies": {
        "primary": "Inter",
        "mono": "JetBrains Mono"
      },
      "scale": {
        "displayLarge": { "size": 32, "weight": 700, "lineHeight": 40 },
        "displayMedium": { "size": 28, "weight": 700, "lineHeight": 36 },
        "displaySmall": { "size": 24, "weight": 600, "lineHeight": 32 },
        "headlineLarge": { "size": 22, "weight": 600, "lineHeight": 28 },
        "headlineMedium": { "size": 20, "weight": 600, "lineHeight": 28 },
        "headlineSmall": { "size": 18, "weight": 600, "lineHeight": 24 },
        "titleLarge": { "size": 16, "weight": 600, "lineHeight": 24 },
        "titleMedium": { "size": 14, "weight": 500, "lineHeight": 20 },
        "titleSmall": { "size": 12, "weight": 500, "lineHeight": 16 },
        "bodyLarge": { "size": 16, "weight": 400, "lineHeight": 24 },
        "bodyMedium": { "size": 14, "weight": 400, "lineHeight": 20 },
        "bodySmall": { "size": 12, "weight": 400, "lineHeight": 16 },
        "labelLarge": { "size": 14, "weight": 500, "lineHeight": 20 },
        "labelMedium": { "size": 12, "weight": 500, "lineHeight": 16 },
        "labelSmall": { "size": 10, "weight": 500, "lineHeight": 12 },
        "code": { "size": 13, "weight": 400, "lineHeight": 20, "family": "JetBrains Mono" }
      }
    },
    "spacing": {
      "xs": 4,
      "sm": 8,
      "md": 12,
      "base": 16,
      "lg": 24,
      "xl": 32,
      "xxl": 48
    },
    "borderRadius": {
      "sm": 4,
      "md": 8,
      "lg": 12,
      "xl": 16,
      "xxl": 20,
      "full": 9999
    },
    "shadows": {
      "sm": "0px 1px 2px rgba(0,0,0,0.15)",
      "md": "0px 2px 8px rgba(0,0,0,0.15)",
      "lg": "0px 4px 12px rgba(0,0,0,0.25)",
      "xl": "0px 8px 24px rgba(0,0,0,0.35)"
    },
    "animation": {
      "duration": {
        "instant": 100,
        "fast": 200,
        "normal": 300,
        "slow": 500
      },
      "easing": {
        "standard": "cubic-bezier(0.4, 0.0, 0.2, 1)",
        "decelerate": "cubic-bezier(0.0, 0.0, 0.2, 1)",
        "accelerate": "cubic-bezier(0.4, 0.0, 1, 1)"
      }
    }
  }
}
```

---

## 12. Frontend Handoff — Flutter Specification

### 12.1 Folder Structure

```
lib/
├── theme/
│   ├── meshnet_theme.dart          ← ThemeData creation
│   ├── colors.dart                 ← Color tokens
│   ├── typography.dart             ← Typography hierarchy
│   ├── dimensions.dart             ← Spacing, radius, elevation
│   └── animations.dart             ← Duration and easing constants
├── widgets/
│   ├── buttons/
│   │   ├── meshnet_button.dart     ← Primary/Ghost/Danger
│   │   ├── icon_button.dart        ← Circular icon button
│   │   └── fab_button.dart         ← Floating action button
│   ├── cards/
│   │   ├── meshnet_card.dart       ← Default/Elevated/Outlined
│   │   └── contact_card.dart       ← Contact card
│   ├── badges/
│   │   └── meshnet_badge.dart      ← Online/Offline/Routing
│   ├── inputs/
│   │   ├── meshnet_input.dart      ← Text field
│   │   └── meshnet_toggle.dart     ← Toggle switch
│   ├── mesh/
│   │   ├── mesh_visualizer.dart    ← Network visualization
│   │   ├── mesh_node.dart          ← Node widget (CustomPainter)
│   │   └── mesh_connection.dart    ← Connection line (CustomPainter)
│   ├── chat/
│   │   ├── message_bubble.dart     ← Message bubble
│   │   ├── encryption_banner.dart  ← Encryption banner
│   │   └── message_status.dart     ← Status indicators
│   └── common/
│       ├── empty_state.dart        ← Empty state
│       ├── snackbar.dart           ← Toast message
│       └── bottom_sheet.dart       ← Bottom sheet
├── screens/
│   ├── home/
│   │   └── home_screen.dart
│   ├── contacts/
│   │   └── contacts_screen.dart
│   ├── chat/
│   │   └── chat_screen.dart
│   ├── pairing/
│   │   ├── pairing_show_screen.dart
│   │   └── pairing_scan_screen.dart
│   ├── settings/
│   │   └── settings_screen.dart
│   └── onboarding/
│       └── onboarding_screen.dart
└── l10n/
    └── app_uz.arb                  ← Translations
```

### 12.2 Core Widget Rules

| Rule | Notes |
|-------|------|
| **State Management** | Riverpod or Bloc |
| **Custom Paint** | `CustomPainter` for the mesh visualizer |
| **Animations** | `AnimationController` + `Tween` or Rive |
| **Fonts** | `GoogleFonts.inter()` and `GoogleFonts.jetBrainsMono()` |
| **Icons** | Material Icons + custom SVG (for mesh) |
| **Dark/Light** | `ThemeData(brightness: ...)` switch |

### 12.3 Animation Performance

- **FPS target:** 60fps minimum
- **Heavy animations:** wrap with `RepaintBoundary`
- **Mesh visualizer:** `CustomPainter` + `shouldRepaint` optimization
- **Lazy loading:** ListView.builder for contacts/chat
- **Image cache:** CachedNetworkImage for avatars

### 12.4 Platform-Specific

| Feature | Implementation |
|---------|---------------|
| **Bluetooth** | `flutter_blue_plus` |
| **Wi-Fi Direct** | `nearby_connections` |
| **QR Scanner** | `mobile_scanner` |
| **QR Generator** | `qr_flutter` |
| **Vibration** | `vibration` package |
| **Haptic** | `HapticFeedback` (Flutter built-in) |
| **Notifications** | `flutter_local_notifications` |
| **Lottie** | `lottie` package (onboarding) |

---

## 13. QA Checklist

### 13.1 Visual Regression

- [ ] Each screen renders correctly in dark mode
- [ ] Each screen renders correctly in light mode
- [ ] Fonts load correctly (Inter, JetBrains Mono)
- [ ] Colors match the tokens (contrast ratio 4.5:1+)
- [ ] Border radius, padding, and margin values are correct
- [ ] Elevation and shadow are correct

### 13.2 Component Testing

- [ ] Primary button — 4 states (default, hover, pressed, disabled)
- [ ] Ghost button — 4 states
- [ ] Danger button — 4 states
- [ ] Toggle — ON/OFF states, animation
- [ ] Input — focus, error, disabled states
- [ ] Badge — all variants (online, offline, routing, etc.)
- [ ] Card — default, elevated, outlined, success, warning, error

### 13.3 Screen Testing

| Screen | Test Case |
|--------|-----------|
| **Home** | Mesh visualization works and the node animation runs |
| **Home** | Pull-to-refresh rescans the network |
| **Contacts** | Online/offline sections are separated correctly |
| **Contacts** | Search works |
| **Contacts** | Swipe actions work |
| **Chat** | The encryption banner is always visible |
| **Chat** | Message statuses are correct (pending → sent → delivered → read) |
| **Chat** | Input field works correctly with the keyboard |
| **Pairing** | The QR code is generated correctly |
| **Pairing** | QR scan opens the camera |
| **Pairing** | Timeout and error states are shown |
| **Settings** | Toggles work |
| **Settings** | The slider works |
| **Onboarding** | Navigation across 3 pages works |
| **Onboarding** | "Skip" works |

### 13.4 Accessibility Testing

- [ ] VoiceOver (iOS) / TalkBack (Android) reads all elements
- [ ] Touch targets are 48dp minimum
- [ ] Contrast ratio 4.5:1 (body text)
- [ ] Color is not the only indicator — icon + text are also present
- [ ] Focus order is logical
- [ ] With reduce motion enabled, animations are simplified

### 13.5 Performance Testing

- [ ] Screen transition < 300ms
- [ ] Message send animation < 500ms
- [ ] Mesh visualizer at 60fps
- [ ] ListView scroll — no jank
- [ ] Cold start < 2 seconds
- [ ] Hot restart < 1 second

### 13.6 Edge Cases

- [ ] Changing the screen size → layout adapts correctly
- [ ] Changing the language → texts render correctly
- [ ] Offline → all screens work (the mesh network waits)
- [ ] Bluetooth off → the "no_network" empty state appears
- [ ] Very long text → truncation or wrapping
- [ ] RTL support (ready for the future)

### 13.7 Security Visual

- [ ] The encryption banner is always visible (on the chat screen)
- [ ] Passwords or tokens are never shown
- [ ] The QR code timeout is shown
- [ ] Session timeout is visually announced

---

## Final Notes

1. **Always think from a user-under-stress perspective** — elements must be
   large, clear, and fast.
2. **The "3-second rule"** — the user must find the needed information within
   3 seconds.
3. **Offline-first mindset** — the app must work fully even without internet.
4. **Purposeful animations** — every animation explains something to the user.
5. **Accessibility is mandatory** — comply with WCAG 2.1 AA standards.

---

**TechCorp Design Team — MeshNet UI/UX Design System v1.0**

*This document is the primary reference for the frontend developer (Flutter)
and the QA test engineer.*