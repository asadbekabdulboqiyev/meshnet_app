# MeshNet — UI/UX Dizayn Tizimi

> **Versiya:** 1.0  
> **Sana:** 2026-08-08  
> **Manzil:** Android (Flutter)  
> **Til:** O'zbek (uz)  
> **Muallif:** TechCorp — Dizayn Bo'limi

---

## Mundarija

1. [Produkt Xarakteri](#1-produkt-xarakteri)
2. [Rang Palitrasi](#2-rang-palitrasi)
3. [Tipografiya](#3-tipografiya)
4. [Ekranlar — Skrin-by-Skrin](#4-ekranlar)
5. [Komponentlar Kutubxonasi](#5-komponentlar)
6. [Holat Belgilari (State Icons)](#6-holat-belgilari)
7. [Micro-Interactions va UX Kinetikasi](#7-micro-interactions)
8. [Empty States](#8-empty-states)
9. [Responsive Qoidalar](#9-responsive)
10. [Accessibility (WCAG 2.1 AA)](#10-accessibility)
11. [Dizayn Tokenlari (JSON)](#11-dizayn-tokenlari)
12. [Frontend Handoff — Flutter Specifikatsiya](#12-handoff)
13. [QA Checklist](#13-qa-checklist)

---

## 1. Produkt Xarakteri

### 1.1 Nima bu?

MeshNet — tabiiy ofatlar, internet blokirovkasi va bosim sharoitlarida **offline P2P mesh tarmoq** orqali aloqa qilish ilovasi. Internet kerak emas. Bluetooth, Wi-Fi Direct va boshqa lokal protokollar orqali qurilmalar bir-biriga ulanadi.

### 1.2 Foydalanuvchi Profili

| Xususiyat | Tavsif |
|-----------|--------|
| **Yosh** | 18–55 |
| **Holat** | Favqulodda, stress ostida, tez yechim kerak |
| **Texnik daraja** | O'rtacha — oddiy foydalanuvchi |
| **Muhit** | Ko'cha, bino, yerosti, yomg'ir, tunning qorong'usi |

### 1.3 Dizayn Tamoyillari

| Tamoyil | Izoh |
|---------|------|
| **Sodda** | Minimal element, maksimal ma'no. Stress ostida 3 soniyada tushunarli |
| **Ishonchli** | Harakat natijasi aniq ko'rinadi. Xabar yetib borganini foydalanuvchi his qilishi kerak |
| **Tez** | Zero-friction. Har bir ekran 2-3 qadamdan oshmasin |
| **Offline-first** | Tarmoq yo'q bo'lsa ham ilova to'liq ishlaydi, faqat mesh ulanish kutadi |
| **Xavfsiz** | Shifrlangan. Banner har doim ko'rinadi. Foydalanuvchi ishonchda bo'lsin |

### 1.4 Emotional Design

- **Stress ostida** — ranglar tinchlantiruvchi (teal), lekin ogohlantirish aniq (orange)
- **Yolg'izlik hissi** — mesh tarmoq vizualizatsiyasi "sen yolg'iz emassan" degan his beradi
- **Ishonch** — shifrlangan banner, yetkazish statusi, xavfsizlik belgilari doimiy ko'rinadi

---

## 2. Rang Palitrasi

### 2.1 Asosiy Palitra (Dark Mode — Primary)

| Token | Rang | HEX | RGB | RGBA | Ishtiroki |
|-------|------|-----|-----|------|-----------|
| `primary-900` | Deep Teal | `#0D3B3E` | 13, 59, 62 | rgba(13,59,62,1) | Orqa fon, header |
| `primary-700` | Teal | `#1A6B6F` | 26, 107, 111 | rgba(26,107,111,1) | Asosiy tugmalar, aktiv holat |
| `primary-500` | Medium Teal | `#2A8F94` | 42, 143, 148 | rgba(42,143,148,1) | Link, sekundar elementlar |
| `primary-300` | Light Teal | `#5CC4C8` | 92, 196, 200 | rgba(92,196,200,1) | Hover, border, divider |
| `primary-100` | Pale Teal | `#D4F0F1` | 212, 240, 241 | rgba(212,240,241,1) | Badge fon, tooltip |

### 2.2 Signal Ranglari (Accent)

| Token | Rang | HEX | Ishtiroki |
|-------|------|-----|-----------|
| `accent-500` | Signal Orange | `#FF6B35` | Ogohlantirish, live badge, favqulodda tugma |
| `accent-400` | Light Orange | `#FF8C5A` | Hover holati |
| `accent-600` | Dark Orange | `#E55A2B` | Press holati |

### 2.3 Coverage Rangi (Yashil)

| Token | Rang | HEX | Ishtiroki |
|-------|------|-----|-----------|
| `success-500` | Signal Green | `#22C55E` | Online, yetkazildi, coverage zone |
| `success-400` | Light Green | `#4ADE80` | Hover, badge |
| `success-600` | Dark Green | `#16A34A` | Press holati |

### 2.4 Status Ranglari

| Token | Rang | HEX | Ishtiroki |
|-------|------|-----|-----------|
| `error-500` | Red | `#EF4444` | Xatolik, yetib bormadi, danger |
| `error-400` | Light Red | `#F87171` | Error badge |
| `warning-500` | Amber | `#F59E0B` | Ogohlantirish, zaif aloqa |
| `warning-400` | Light Amber | `#FBBF24` | Warning badge |
| `info-500` | Blue | `#3B82F6` | Ma'lumot, routing status |
| `info-400` | Light Blue | `#60A5FA` | Info badge |

### 2.5 Neutral Ranglar

| Token | Rang | HEX | Ishtiroki |
|-------|------|-----|-----------|
| `neutral-950` | Near Black | `#0A0A0A` | Matn (dark mode) |
| `neutral-900` | Dark | `#171717` | Asosiy matn |
| `neutral-800` | | `#262626` | Card fon (dark mode) |
| `neutral-700` | | `#404040` | Ikkinchi darajali matn |
| `neutral-500` | | `#737373` | Placeholder, disabled |
| `neutral-400` | | `#A3A3A3` | Border, divider |
| `neutral-300` | | `#D4D4D4` | Light border |
| `neutral-200` | | `#E5E5E5` | Light card bg |
| `neutral-100` | | `#F5F5F5` | Off-white fon (light mode) |
| `neutral-50` | Off-White | `#FAFAFA` | Pastki qism, bottom sheet |

### 2.6 Tema Almashtirish

```
Dark Mode (primary):  Background #0D3B3E → Card #1A2A2B → Text #F5F5F5
Light Mode (secondary): Background #FAFAFA → Card #FFFFFF → Text #0A0A0A
```

**Default tema:** Dark mode. Foydalanuvchi sozlamalardan almashtira oladi.

---

## 3. Tipografiya

### 3.1 Shriftlar

| Shrift | Type | Google Fonts | Qo'llanilishi |
|--------|------|--------------|---------------|
| **Inter** | Sans-serif | `fonts.google.com/specimen/Inter` | UI matnlari, tugmalar, sarlavhalar |
| **JetBrains Mono** | Monospace | `fonts.google.com/specimen/JetBrains+Mono` | IP manzillar, haskesh, MAC, texnik matn |
| **Roboto Mono** | Monospace | `fonts.google.com/specimen/Roboto+Mono` | Alternativ (agar JetBrains Muto mos kelmasa) |

### 3.2 Tipografik Iyerarxiya

| Qiymat | Dark Mode | Light Mode | Weight | Size | Line Height | Tracking |
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

### 3.3 Qoidalari

- **Maksimum qator uzunligi:** 60 ta belgi (chat xabarlari — 45 belgi)
- **Spacing:** Sarlavha va matn orasida 8sp, matn va matn orasida 4sp
- **Contrast ratio:** WCAG AA — 4.5:1 (body), 3:1 (large text)
- **Monospace:** Faqat texnik ma'lumotlar uchun (IP, MAC, hash). Oddiy matnda ishlatmaslik

---

## 4. Ekranlar — Skrin-by-Skrin

### 4.1 Home / Network Ekranı

**Maqsad:** Foydalanuvchi mesh tarmoq holatini 1 soniyada tushunishi kerak.

#### Layout (top → bottom):

```
┌─────────────────────────────────────┐
│  🟢 MeshNet              ⚙️       │  ← Top App Bar
│  Tarmoq: Faol | 3 ta ulanish       │
├─────────────────────────────────────┤
│                                     │
│      ╭───╮     ╭───╮               │
│      │ 📱│─ ─ ─│ 📱│               │  ← Mesh Visual
│      ╰─┬─╯     ╰─┬─╯               │     (bulut turindagi
│        │    📱    │                 │      hub'lar,
│        │─ ─ │ ─ ─│                 │      tutarich chiziqlar)
│        │    │     │                 │
│      ╭─┴─╮       ╰─╮               │
│      │ 📱│    ╭───╮ │               │
│      ╰───╯    │ 📱│ │               │
│               ╰───╯ │               │
│                                     │
│  👤 Sen (Sen): #A7F3D0             │  ← O'zim belgilanishi
│  📍 0.3 km radius                  │     (yashil doira)
├─────────────────────────────────────┤
│  📡 Tarmoq Ma'lumotlari            │
│  ├─ Faol peer'lar: 3               │
│  ├─ Routing xop: 2                 │
│  ├─ Signal kuchi: ⚡ Yuqori        │
│  └─ Oxirgi yangilanish: 2 soniya   │
├─────────────────────────────────────┤
│  [ 📡 Ulash ]  [ 💬 Xabar ]       │  ← Primary Actions
├─────────────────────────────────────┤
│  🏠    📋    💬    ⚙️              │  ← Bottom Navigation
│  Tarmoq Kontaktlar Xabarlar Sozlash│
└─────────────────────────────────────┘
```

#### Komponentlar tafsiloti:

| Element | Tavsif | Animatsiya |
|---------|--------|------------|
| **Mesh Visual** | Yumaloq node'lar + dashed chiziqlar | Pulsatsiya (har 3 soniyada) |
| **Node rangi** | O'zim = yashil, online = teal, offline = gray | Fade in/out |
| **Chiziq** | Faol aloqa = solid teal, zaif = dashed amber, yo'q = gray | Stroke animation |
| **"Sen" marker** | Yashil doira + label | Gentle glow |
| **Tarmoq ma'lumotlari** | Card ichida, open state | Slide up |

#### Interaktivlik:

- Node'ga bosil → profil qisqa (name + signal)
- Mesh vizualni zoom qilish mumkin (pinch-to-zoom)
- Pull-to-refresh → tarmoq qayta skanerlash

---

### 4.2 Contacts List (Kontaktlar)

**Maqsad:** Offline/online holatini bir qarashda aniqlash.

#### Layout:

```
┌─────────────────────────────────────┐
│  📋 Kontaktlar           🔍  ➕   │  ← Top Bar
├─────────────────────────────────────┤
│  Qidirish...                       │  ← Search bar
├─────────────────────────────────────┤
│  🟢 ONLINE (2)                     │  ← Section header
├─────────────────────────────────────┤
│  ┌─────────────────────────────┐   │
│  │ 🟢  Karimov Bobur          │   │  ← Online contact
│  │     📡 0.1 km | Signal ⚡   │   │     (teal left border)
│  │     [ 💬 Yozish ] [ 📞 ]   │   │
│  └─────────────────────────────┘   │
│  ┌─────────────────────────────┐   │
│  │ 🟢  Raximova Nilufar       │   │
│  │     📡 0.3 km | Signal ⚡   │   │
│  │     [ 💬 Yozish ] [ 📞 ]   │   │
│  └─────────────────────────────┘   │
├─────────────────────────────────────┤
│  ⚫ OFFLINE (5)                    │  ← Section header
├─────────────────────────────────────┤
│  ┌─────────────────────────────┐   │
│  │ ⚫  Toshmatov Sardor       │   │  ← Offline contact
│  │     📡 | Oxirgi: 15 daqiqa  │   │     (gray left border)
│  │     [ 📞 Qo'ng'iroq ]      │   │
│  └─────────────────────────────┘   │
│  ┌─────────────────────────────┐   │
│  │ 🔵  Valiyev Jamshid        │   │  ← Routing (signal relay)
│  │     📡 0.5 km | Signal 💪   │   │     (blue left border)
│  │     [ 💬 Yozish ] [ 📞 ]   │   │
│  └─────────────────────────────┘   │
│  ...                               │
├─────────────────────────────────────┤
│  🏠    📋    💬    ⚙️              │
└─────────────────────────────────────┘
```

#### Kontakt Kartasi Tarkibiy Qismlari:

| Element | Tavsif |
|---------|--------|
| **Avatar** | 44dp radius yumaloq, default initial bilan |
| **Holat doti** | 12dp, chap tomonda overloap. Green=online, Gray=offline, Blue=routing |
| **Ism** | `title-medium`, 14sp |
| **Masofa/SIGNAL** | `body-small`, 12sp. Masofa yo'q bo'lsa "Noma'lum" |
| **Amal tugmalari** | 40dp height, icon + text. Primary = teal, Ghost = outline |

#### Interaktivlik:

- Swipe left → o'chirish/gizlash
- Long press → profil batafsil
- Har bir kontakt offline/online banneri bilan

---

### 4.3 Chat Ekranı

**Maqsad:** Shifrlangan xabar almashish. Xabar yetib borganini aniq ko'rish kerak.

#### Layout:

```
┌─────────────────────────────────────┐
│  ←  Karimov Bobur     🟢 Online   │  ← Header
│     📡 0.1 km | Signal ⚡          │
├─────────────────────────────────────┤
│  🔒 Bu suhbat E2E shifrlangan     │  ← Encryption Banner
│  │  Xabarlar faqat siz va          │     (pastel teal fon,
│  │  qabul qiluvchi ko'ra oladi     │      teal text)
│  🔒                                │
├─────────────────────────────────────┤
│                                     │
│  14:32                             │  ← Timestamp
│  ┌─────────────────────────────┐   │
│  │ Assalomu alaykum!          │   │  ← incoming (teal bg,
│  │ Siz qayerdasiz?            │   │     white text)
│  └─────────────────────────────┘   │
│              14:33                 │
│        ┌───────────────────────┐   │
│        │ Va alaykum assalom!   │   │  ← outgoing (primary-700 bg,
│        │ Men Markaziy ko'chada │   │     white text)
│        └───────────────────────┘   │
│                        ✓✓          │  ← delivered (teal)
│                                     │
│  14:35                             │
│  ┌─────────────────────────────┐   │
│  │ 🗺️ Joylashuv: Markaziy    │   │  ← location card
│  │    ko'cha, 45-uy            │   │
│  │    [ 📍 Xaritada ko'rish ]  │   │
│  └─────────────────────────────┘   │
│                                     │
│  14:36                             │
│        ┌───────────────────────┐   │
│        │ Xabar yetib bordimi?  │   │
│        └───────────────────────┘   │
│                     ⏳              │  ← pending (gray, pulsing)
│                                     │
├─────────────────────────────────────┤
│  ┌─────────────────────────┐  📎  │  ← Input bar
│  │ Xabar yozing...         │  🎤  │
│  └─────────────────────────┘  ➤   │
└─────────────────────────────────────┘
```

#### Xabar Statuslari:

| Status | Belgi | Tavsif |
|--------|-------|--------|
| **Pending** | ⏳ (pulsing) | Yuborilmoqda. Mesh tarmoq qidirilmoqda |
| **Sent** | ✓ (gray) | Yaqin node'ga yetdi |
| **Delivered** | ✓✓ (teal) | Qabul qiluvchiga yetdi |
| **Read** | ✓✓ (green) | O'qildi |
| **Failed** | ⚠️ (red) | Yetib bormadi. Qayta yuborish tugmasi |
| **Routing** | 🔄 (blue) | Boshqa node orqali yo'naltirilmoqda |

#### Shifrlangan Banner:

- Har doim chat boshida ko'rinadi
- Background: `primary-100` (10% opacity) — dark mode: `#1A2A2B`
- Matn: `primary-500`
- Ikon: 🔒 yoki SVG lock icon
- Dismiss qilinmaydi — doimiy ko'rinadi

#### Interaktivlik:

- Send tugmasi bosilganda → tugma icon'i swipe animation (yozuv chizig'i → paper plane)
- Message long press → Reply, Copy, Delete
- Swipe down → timestamp'lar ko'rinadi
- Typing indicator → 3 nuqta pulsation

---

### 4.4 Pairing (Juftlashtirish) Ekranı

**Maqsad:** QR orqali qurilmalarni ulash. Oddiy va tez.

#### 4.4.1 QR Ko'rsatish (Meni boshqa qurilmaga qo'shish)

```
┌─────────────────────────────────────┐
│  ←  Juftlash          ❌           │
├─────────────────────────────────────┤
│                                     │
│  📱 Seni qurilmani boshqa          │
│  qurilmalarga ko'rsat              │
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
│  📡 Signal: ⚡ Kuchli              │
│  ⏱️ Muddati: 5:00 daqiqa          │
│  🔒 Faqat 1 marta ishlatiladi     │
│                                     │
│  [ 🔄 Yangilash ]                  │  ← QR refresh
│                                     │
├─────────────────────────────────────┤
│  💡 Maslahat:                      │
│  • QR ni boshqa qurilmaga ko'rsating│
│  • Bluetooth yoqilgan bo'lsin       │
│  • 2 metr yaqinlikda turing        │
└─────────────────────────────────────┘
```

#### 4.4.2 QR Skanerlash (Boshqa qurilmani qo'shish)

```
┌─────────────────────────────────────┐
│  ←  Juftlash          ❌           │
├─────────────────────────────────────┤
│                                     │
│  📷 QR kodni skanerlash            │
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
│  📍 QR kodni markazga joylashtiring │
│                                     │
│  ⏳ Kutish...                       │  ← Scanning indicator
│                                     │
│  [ 💡 Qo'lda kiritish ]            │  ← Manual fallback
└─────────────────────────────────────┘
```

#### 4.4.3 Pairing Natijasi

```
┌─────────────────────────────────────┐
│                                     │
│         ✅                          │  ← Success state
│                                     │
│    Muvaffaqiyatli ulandi!          │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ 📱 Karimov Bobur's Phone   │   │  ← Paired device card
│  │ 📡 Signal: ⚡ Yuqori        │   │
│  │ 🔒 Shifrlangan: Ha         │   │
│  │ ⏱️ Ulangan: 14:35          │   │
│  └─────────────────────────────┘   │
│                                     │
│  [ ✅ Tushundim, davom etish ]    │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ ⚠️ Xatolik yuz berdi!      │   │  ← Error state
│  │                              │   │
│  │ Sabab: Bluetooth o'chirilgan │   │
│  │ [ 🔧 Bluetooth yoqish ]    │   │
│  │ [ 🔄 Qayta urinish ]       │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ ⏱️ Vaqt tugadi!            │   │  ← Timeout state
│  │                              │   │
│  │ QR kod muddati tugadi       │   │
│  │ [ 🔄 Yangi QR yaratish ]   │   │
│  └─────────────────────────────┘   │
│                                     │
└─────────────────────────────────────┘
```

---

### 4.5 Settings (Sozlamalar) Ekranı

```
┌─────────────────────────────────────┐
│  ⚙️  Sozlamalar                     │
├─────────────────────────────────────┤
│                                     │
│  👤 PROFIL                          │
│  ├─ Isming: Karimov Bobur          │
│  ├─ Status: Yordam kerak            │
│  └─ Avatar: [📷 O'zgartirish]      │
│                                     │
│  📡 TARMOQ                          │
│  ├─ Auto-start: [🟢 ON]            │
│  │  (Ilova ochilganda avto ulanish) │
│  ├─ Transport ustuvorligi:          │
│  │  1. Bluetooth ← drag to reorder  │
│  │  2. Wi-Fi Direct                 │
│  │  3. Hotspot                      │
│  ├─ Mesh radius: ◀━●━━▶ 0.5 km    │
│  └─ Max peer: [5]                  │
│                                     │
│  🔒 XAVFSIZLIK                      │
│  ├─ Shifrlash: E2E (Always On)     │
│  ├─ Key management: [Device-local]  │
│  └─ Auto-delete: [7 kun]           │
│                                     │
│  🔔 BILDIRISHLAR                    │
│  ├─ Xabar ovozi: [🟢 ON]           │
│  ├─ Vibration: [🟢 ON]             │
│  └─ Faqat favqulodda: [⚫ OFF]    │
│                                     │
│  🎨 INTERFEYS                       │
│  ├─ Tema: [🌙 Dark ○ Light]       │
│  ├─ Katta matn: [⚫ OFF]          │
│  └─ Til: [O'zbek 🇺🇿]             │
│                                     │
│  ℹ️  ABOUT                           │
│  ├─ Versiya: 1.0.0                 │
│  ├─ Licenses                       │
│  └️ Privacy Policy                  │
│                                     │
└─────────────────────────────────────┘
```

#### Transport Ustuvorligi — Drag & Drop:

- Har bir transport qatorini siljitib tartiblashtirish
- Siljish paytida: 4dp elevation, subtle shadow
- Qo'yilganda: bounce animation (150ms)
- Faol transport: teal dot indicator

---

### 4.6 Onboarding / Intro (3 Bosqich)

#### Bosqich 1: "Nima bu?"

```
┌─────────────────────────────────────┐
│                                     │
│         🌐                          │  ← Hero illustration
│       / | \                         │     (mesh network
│      📱─📱─📱                      │      animated Lottie)
│                                     │
│  MeshNet — Sizning                   │
│  shaxsiy mesh tarmog'ingiz         │
│                                     │
│  Internet kerak emas. Bluetooth     │
│  va Wi-Fi Direct orqali atrofdagi   │
│  qurilmalar bilan to'g'ridan-       │
│  to'g'ri aloqa qiling.             │
│                                     │
│  Tabiiy ofatlar, blokirovkalar     │
│  vafavqulodda holatlar uchun        │
│  yaratilgan.                        │
│                                     │
│              ● ○ ○                  │  ← Page indicator
│                                     │
│  [ Keyingi ➤ ]                     │
│  [ O'tkazib yuborish ]             │
└─────────────────────────────────────┘
```

#### Bosqich 2: "Qanday ishlaydi?"

```
┌─────────────────────────────────────┐
│                                     │
│         📱                          │  ← Hero illustration
│        ↕️                           │     (devices pairing
│         📱                          │      animation)
│                                     │
│  1️⃣ Ilovani oching                  │
│  2️⃣ QR kodni skanerlang            │
│  3️⃣ Avtomatik ravishda ulaning     │
│                                     │
│  ┌─────────────────────────────┐   │
│  │  ✅ Bluetooth zarur          │   │  ← Requirements list
│  │  ✅ Wi-Fi Direct zarur      │   │
│  │  ✅ Bluetooth ruxsati kerak  │   │
│  └─────────────────────────────┘   │
│                                     │
│              ○ ● ○                  │
│                                     │
│  [ Keyingi ➤ ]                     │
│  [ Orqaga ]                        │
└─────────────────────────────────────┘
```

#### Bosqich 3: "Tayyor!"

```
┌─────────────────────────────────────┐
│                                     │
│         🚀                          │  ← Hero illustration
│       / | \                         │     (success/checkmark
│      📱─📱─📱                      │      animation)
│                                     │
│  Hamma tayyor!                      │
│                                     │
│  Endi siz offline holda ham        │
│  atrofdagilar bilan aloqa           │
│  qila olasiz.                       │
│                                     │
│  💡 Maslahat:                       │
│  Birinchi qadam — yaqiningizdagi   │
│  odam bilan juftlang!              │
│                                     │
│              ○ ○ ●                  │
│                                     │
│  [ 🚀 Boshlash ]                   │
│  [ Orqaga ]                        │
└─────────────────────────────────────┘
```

#### Onboarding Animatsiyalari:

- Har bir sahifaga o'tish → horizontal page transition (300ms, ease-out)
- Lottie animatsiyalar → loop, 2s cycle
- "Boshlash" tugmasi → scale-up bounce (0.9 → 1.05 → 1.0, 400ms)

---

## 5. Komponentlar Kutubxonasi

### 5.1 Card (Karta)

#### Variantlar:

| Variant | Tavsif | Use Case |
|---------|--------|----------|
| `card-default` | Neutral-800 fon, 12dp radius, 1dp border | Oddiy kontent |
| `card-elevated` | Neutral-800 + 2dp elevation | Asosiy kontent, tap qilinadigan |
| `card-outlined` | Transparent fon, 1dp border | Ikkinchi darajali |
| `card-success` | Success-500 left border | Muvaffaqiyat holati |
| `card-warning` | Warning-500 left border | Ogohlantirish |
| `card-error` | Error-500 left border | Xatolik |

#### Spetsifikatsiya:

```
Padding: 16dp (horizontal), 16dp (vertical)
Border radius: 12dp
Border: 1dp solid neutral-700 (dark), neutral-300 (light)
Elevation: 0dp (default), 2dp (elevated)
Shadow: rgba(0,0,0,0.15) 0px 2px 8px (elevated only)
```

### 5.2 Button (Tugmalar)

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

**Holatlar:**
- Default → primary-700
- Hover/Focus → primary-500
- Pressed → primary-900
- Disabled → neutral-700, text neutral-500

**Animatsiya:** Press paytida scale(0.97), 100ms

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

| Variant | Rang | Tavsif |
|---------|------|--------|
| `badge-online` | success-500 | Foydalanuvchi online |
| `badge-offline` | neutral-500 | Offline |
| `badge-routing` | info-500 | Signaling qilyapti |
| `badge-warning` | warning-500 | Zaif signal |
| `badge-error` | error-500 | Xatolik |
| `badge-live` | accent-500 | Live/hozir (pulsing) |
| `badge-encrypted` | primary-500 | Shifrlangan |

#### Spetsifikatsiya:

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

## 6. Holat Belgilari (State Icons)

### 6.1 Network States

| State | Icon | Rang | Matn | Tavsif |
|-------|------|------|------|--------|
| `no_network` | 📡❌ | neutral-500 | "Tarmoq topilmadi" | Bluetooth/Wi-Fi o'chirilgan |
| `no_peers` | 👥❌ | neutral-500 | "Hech kim yaqinda yo'q" | Atrofda peer yo'q |
| `peer_detected` | 👥✅ | success-500 | "3 ta qurilma topildi" | Peer'lar aniqlandi |
| `connected` | 🟢 | success-500 | "Tarmoqqa ulangan" | To'liq ulanish |
| `disconnected` | ⚫ | neutral-500 | "Ulanish uzildi" | Aloqa uzildi |
| `connecting` | 🔄 | info-500 | "Ulanmoqda..." | Jarayonda |
| `weak_signal` | ⚡⚠️ | warning-500 | "Signal zaif" | Sifat past |

### 6.2 Message States

| State | Icon | Rang | Tavsif |
|-------|------|------|--------|
| `sending` | ⏳ (pulsing) | neutral-500 | Yuborilmoqda |
| `sent` | ✓ | neutral-400 | Yuborildi |
| `delivered` | ✓✓ | primary-500 | Yetkazildi |
| `read` | ✓✓ | success-500 | O'qildi |
| `failed` | ⚠️ | error-500 | Yetib bormadi |

### 6.3 Connection Quality

| Level | Icon | Rang | Tavsif |
|-------|------|------|--------|
| Excellent | ⚡⚡⚡ | success-500 | Kuchli signal |
| Good | ⚡⚡ | success-400 | Yaxshi |
| Fair | ⚡ | warning-500 | O'rtacha |
| Poor | ⚡⚠️ | error-500 | Zaif |

---

## 7. Micro-Interactions

### 7.1 Message Yuborish Tugmasi

**Animatsiya ketma-ketligi:**

1. **Tap** → Tugma scale(0.95), 80ms
2. **Input clear** → Matn fade out, 120ms
3. **Icon transition** → Keyboard icon → Paper plane icon, rotation(360°), 250ms
4. **Send** → Paper plane → topga uchish (translateY -20dp), 300ms, ease-in
5. **Message appear** → Chat ichida slide-up + fade-in, 200ms
6. **Status update** → Pending (⏳ pulsing) → Sent (✓) → Delivered (✓✓), sequential

### 7.2 Bluetooth Ulash Choreography

**Pairing jarayoni:**

1. **QR Skanerlash** → Corner brackets animate (scale 1.0 → 1.1 → 1.0, pulse)
2. **Device found** → Vibration (100ms), sound tone (optional)
3. **Connecting** → Both device icons orbit each other, 2s loop
4. **Connected** → Both icons settle, green ring expand + fade, 400ms
5. **Encryption established** → Lock icon appear with scale bounce, 300ms

### 7.3 Mesh Node Animatsiyalari

| Holat | Animatsiya |
|-------|------------|
| **Node join** | Fade-in + scale(0.5 → 1.0), 300ms |
| **Node leave** | Fade-out + scale(1.0 → 0.5), 300ms |
| **Signal strength** | Node pulse speed = signal kuchi (kuchli = tez) |
| **Connection line** | Dashed stroke animate, continuous |
| **Data transfer** | Particles along connection line, 2s loop |

### 7.4 Navigation Transitions

| Transition | Animatsiya |
|------------|------------|
| **Screen enter** | Slide from right, 300ms, ease-out |
| **Screen exit** | Slide to left, 250ms, ease-in |
| **Tab switch** | Crossfade, 200ms |
| **Bottom sheet open** | Slide up + backdrop fade, 350ms |
| **Dialog open** | Scale(0.9 → 1.0) + fade, 250ms |
| **Card tap** | Scale(1.0 → 0.98), 100ms |

### 7.5 Haptic Feedback

| Joylashuv | Haptic |
|-----------|--------|
| **Message received** | Light impact |
| **Message sent** | Success |
| **Error** | Error |
| **QR scan success** | Medium impact |
| **Pairing connected** | Heavy impact |
| **Button press** | Selection |

---

## 8. Empty States

### 8.1 Tarmoq Yo'q

```
┌─────────────────────────────────────┐
│                                     │
│         📡❌                        │  ← Illustration (48dp)
│                                     │
│    Tarmoq topilmadi                │  ← headline-medium
│                                     │
│    Bluetooth va Wi-Fi yoqilganligini│  ← body-large
│    tekshiring.                      │
│                                     │
│    [ 🔧 Sozlamalarni ochish ]      │  ← Primary button
│                                     │
│    ┌─────────────────────────┐     │
│    │ Bluetooth: [🟢 Yoqish] │     │  ← Quick settings
│    │ Wi-Fi:     [🟢 Yoqish] │     │
│    └─────────────────────────┘     │
│                                     │
└─────────────────────────────────────┘
```

### 8.2 Peer Yo'q

```
┌─────────────────────────────────────┐
│                                     │
│         👥❌                        │
│                                     │
│    Atrofda hech kim yo'q           │
│                                     │
│    Mesh tarmoqqa ulanish uchun     │
│    yaqiningizdagi odamlar ham      │
│    MeshNet ishlatishi kerak.       │
│                                     │
│    [ 📲 Do'stlarga ulashish ]      │
│    [ 🔄 Qayta skanerlash ]         │
│                                     │
└─────────────────────────────────────┘
```

### 8.3 Roaming / Yaqsiz Muhit

```
┌─────────────────────────────────────┐
│                                     │
│         🗺️⚠️                       │
│                                     │
│    Signal zaif                     │
│                                     │
│    Jismoniy to'siqlar signal       │
│    sifatiga ta'sir qilyapti.       │
│    ochiq joyga chiqishga harakat   │
│    qiling.                          │
│                                     │
│    📡 Signal kuchi: ▓▓░░░ (35%)    │
│                                     │
│    [ 🔄 Qayta ulanish ]           │
│                                     │
└─────────────────────────────────────┘
```

### 8.4 Chat Bo'sh

```
┌─────────────────────────────────────┐
│                                     │
│         💬                          │
│                                     │
│    Suhbat hali boshlanmagan        │
│                                     │
│    Birinchi xabaringizni yozing!   │
│                                     │
│    🔒 Xabarlar E2E shifrlangan    │
│                                     │
│  ┌────────────────────────────┐    │
│  │ Xabar yozing...      🎤 ➤ │    │
│  └────────────────────────────┘    │
│                                     │
└─────────────────────────────────────┘
```

### 8.5 Kontaklar Bo'sh

```
┌─────────────────────────────────────┐
│                                     │
│         📋                          │
│                                     │
│    Kontaktlar yo'q                 │
│                                     │
│    Boshqa foydalanuvchilar bilan   │
│    juftlash orqali kontakt          │
│    qo'shing.                        │
│                                     │
│    [ ➕ Yangi kontakt qo'shish ]   │
│                                     │
└─────────────────────────────────────┘
```

---

## 9. Responsive Qoidalar

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
| **Message input** | "Xabar matni" | "Yozing va yuborish uchun bosing" |
| **Send button** | "Xabar yuborish" | — |
| **QR Scanner** | "QR kod skanerlash" | "Kamerani QR kodga yo'naltiring" |
| **Contact card** | "[Ism], [holat]" | "Suhbat ochish uchun bosing" |
| **Toggle** | "[nom], [yoqilgan/o'chirilgan]" | "O'zgartirish uchun bosing" |
| **Network status** | "Tarmoq holati: [status]" | — |

### 10.4 Motion

- Reduce motion: Respect `prefers-reduced-motion`
- Alternative: Static indicators instead of animations
- Critical animations (sending, connecting) still work but simplified

### 10.5 Color Independence

- Status never relies on color alone
- Always paired with icon + text
- Example: "Online" = green dot + ✓ icon + "Online" text

---

## 11. Dizayn Tokenlari (JSON)

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

## 12. Frontend Handoff — Flutter Spetsifikatsiya

### 12.1 Papka Tuzilishi

```
lib/
├── theme/
│   ├── meshnet_theme.dart          ← ThemeData yaratish
│   ├── colors.dart                 ← Rang tokenlari
│   ├── typography.dart             ← Shrift iyerarxiyasi
│   ├── dimensions.dart             ← Spacing, radius, elevation
│   └── animations.dart             ← Duration va easing constants
├── widgets/
│   ├── buttons/
│   │   ├── meshnet_button.dart     ← Primary/Ghost/Danger
│   │   ├── icon_button.dart        ← Circular icon button
│   │   └── fab_button.dart         ← Floating action button
│   ├── cards/
│   │   ├── meshnet_card.dart       ← Default/Elevated/Outlined
│   │   └── contact_card.dart       ← Kontakt kartasi
│   ├── badges/
│   │   └── meshnet_badge.dart      ← Online/Offline/Routing
│   ├── inputs/
│   │   ├── meshnet_input.dart      ← Text field
│   │   └── meshnet_toggle.dart     ← Toggle switch
│   ├── mesh/
│   │   ├── mesh_visualizer.dart    ← Network vizualizatsiya
│   │   ├── mesh_node.dart          ← Node widget (CustomPainter)
│   │   └── mesh_connection.dart    ← Connection line (CustomPainter)
│   ├── chat/
│   │   ├── message_bubble.dart     ← Xabar pufagi
│   │   ├── encryption_banner.dart  ← Shifrlash banneri
│   │   └── message_status.dart     ← Status indicatorlari
│   └── common/
│       ├── empty_state.dart        ← Bo'sh holat
│       ├── snackbar.dart           ← Toast xabar
│       └── bottom_sheet.dart       ← Pastki panel
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
    └── app_uz.arb                  ← O'zbek tarjimalari
```

### 12.2 Asosiy Widget Qoidalari

| Qoida | Izoh |
|-------|------|
| **State Management** | Riverpod yoki Bloc |
| **Custom Paint** | Mesh visualizer uchun `CustomPainter` |
| **Animations** | `AnimationController` + `Tween` yoki Rive |
| **Fonts** | `GoogleFonts.inter()` va `GoogleFonts.jetBrainsMono()` |
| **Icons** | Material Icons + custom SVG (mesh uchun) |
| **Dark/Light** | `ThemeData(brightness: ...)` switch |

### 12.3 Animatsiya Performance

- **FPS target:** 60fps minimum
- **Heavy animations:** `RepaintBoundary` bilan wrapping
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

- [ ] Har bir ekran dark mode da to'g'ri ko'rinadi
- [ ] Har bir ekran light mode da to'g'ri ko'rinadi
- [ ] Font'lar to'g'ri yuklandi (Inter, JetBrains Mono)
- [ ] Ranglar tokenlarga mos (contrast ratio 4.5:1+)
- [ ] Border radius, padding, margin qiymatlari to'g'ri
- [ ] Elevation va shadow to'g'ri

### 13.2 Component Testing

- [ ] Primary button — 4 holat (default, hover, pressed, disabled)
- [ ] Ghost button — 4 holat
- [ ] Danger button — 4 holat
- [ ] Toggle — ON/OFF holatlari, animatsiya
- [ ] Input — Focus, error, disabled holatlari
- [ ] Badge — Barcha variantlar (online, offline, routing, etc.)
- [ ] Card — Default, elevated, outlined, success, warning, error

### 13.3 Screen Testing

| Screen | Test Case |
|--------|-----------|
| **Home** | Mesh vizualizatsiya to'g'ri ishlaydi, node'lar animatsiyasi |
| **Home** | Pull-to-refresh tarmoqni qayta skanerlaydi |
| **Contacts** | Online/offline bo'limlari to'g'ri ajratilgan |
| **Contacts** | Qidiruv ishlaydi |
| **Contacts** | Swipe actions ishlaydi |
| **Chat** | Shifrlangan banner doimiy ko'rinadi |
| **Chat** | Xabar statuslari to'g'ri (pending → sent → delivered → read) |
| **Chat** | Input field keyboard bilan to'g'ri ishlaydi |
| **Pairing** | QR code to'g'ri generatsiya qilinadi |
| **Pairing** | QR scan kamerani ochadi |
| **Pairing** | Timeout va error holatlari ko'rsatiladi |
| **Settings** | Toggle'lar ishlaydi |
| **Settings** | Slider ishlaydi |
| **Onboarding** | 3 sahifaga o'tish ishlaydi |
| **Onboarding** | "O'tkazib yuborish" ishlaydi |

### 13.4 Accessibility Testing

- [ ] VoiceOver (iOS) / TalkBack (Android) barcha elementlarni o'qiydi
- [ ] Touch targetlar 48dp minimum
- [ ] Kontrast ratio 4.5:1 (body text)
- [ ] Rang yagona emas — icon + text ham bor
- [ ] Focus order mantiqiy
- [ ] Reduce motion ishlatsa animatsiyalar soddalashadi

### 13.5 Performance Testing

- [ ] Screen transition < 300ms
- [ ] Message send animation < 500ms
- [ ] Mesh visualizer 60fps
- [ ] ListView scroll — jank yo'q
- [ ] Cold start < 2 soniya
- [ ] Hot restart < 1 soniya

### 13.6 Edge Cases

- [ ] Ekran o'lchamini o'zgartirish → layout to'g'ri moslashadi
- [ ] Til o'zgartirish → matnlar to'g'ri ko'rinadi
- [ ] Offline → barcha ekranlar ishlaydi (mesh tarmoq kutadi)
- [ ] Bluetooth o'chirilgan → "no_network" empty state ko'rinadi
- [ ] Barcha text uzundan uzun → truncation yoki wrapping
- [ ] RTL support (kelajak uchun tayyor)

### 13.7 Security Visual

- [ ] Shifrlangan banner har doim ko'rinadi (chat ekranida)
- [ ] Hech qachon parol yoki token ko'rsatilmaydi
- [ ] QR code timeout ko'rsatiladi
- [ ] Session timeout vizual bildirish

---

## Yakuniy Eslatmalar

1. **Har doim stress ostida foydalanuvchi perspektivadan fikrlang** — elementlar katta, aniq, tez ishlaydigan bo'lishi kerak.
2. **"3 soniya qoidasi"** — foydalanuvchi kerakli ma'lumotni 3 soniyada topishi kerak.
3. **Offline-first mentalitet** — internet bo'lmaganda ham ilova to'liq ishlashi kerak.
4. **Animatsiyalar maqsadli** — har bir animatsiya foydalanuvchiga biror narsa tushuntiradi.
5. **Accessibility majburiy** — WCAG 2.1 AA standartlariga rioya qilish shart.

---

**TechCorp Dizayn Bo'limi — MeshNet UI/UX Dizayn Tizimi v1.0**

*Bu hujjat frontend dasturchi (Flutter) va QA testchi uchun asosiy qo'llanma hisoblanadi.*
