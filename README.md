# 🚦 Cloud-Based Smart Traffic & Route Optimization Android App

A production-ready intelligent navigation system that integrates mobile crowdsourcing, 
deep learning congestion prediction, multi-objective route optimization, and weather-aware 
routing into a unified Android application.

---

## 📱 Screenshots

<table>
  <tr>
    <td align="center"><b>Onboarding</b></td>
    <td align="center"><b>Map</b></td>
  </tr>
  <tr>
    <td><img width="300" src="https://github.com/user-attachments/assets/4a768a3a-63e5-414a-a98c-b5910cd5275a" /></td>
    <td><img width="300" src="https://github.com/user-attachments/assets/fa0c3ac9-b797-449b-ace0-201c93ab3ab0" /></td>
  </tr>
  <tr>
    <td align="center"><b>Navigation</b></td>
    <td align="center"><b>Analytics</b></td>
  </tr>
  <tr>
    <td><img width="300" src="https://github.com/user-attachments/assets/18850799-e313-4114-a800-aed2878a17e1" /></td>
    <td><img width="300" src="https://github.com/user-attachments/assets/b35558a0-91ab-4069-91df-51b9ee8a5692" /></td>
  </tr>
  <tr>
    <td align="center"><b>Leaderboard</b></td>
    <td></td>
  </tr>
  <tr>
    <td><img width="300" src="https://github.com/user-attachments/assets/78bba8d1-68a1-42fb-a2c7-fdeea010a890" /></td>
    <td></td>
  </tr>
</table>


---

## 🏗️ System Architecture

The system follows a four-tier cloud-native architecture:

- **Android Client Layer** — Kotlin/Java app with Google Maps, GPS tracking, voice navigation
- **Cloud Backend Layer** — FastAPI on Google Cloud Run with auto-scaling
- **ML Platform Layer** — STGAT-FusionNet + HistGB ensemble model
- **Data Layer** — Firebase Firestore with 9 collections

---

## ✨ Features

- 🗺️ **Real-time congestion visualization** — color-coded map overlay (Low to Severe)
- 🤖 **AI congestion prediction** — next-hour forecasting using STGAT-FusionNet
- 🛣️ **Multi-objective routing** — Fastest, Fuel Efficient, Low Traffic, Scenic modes
- 🌦️ **Weather-aware routing** — checkpoint-level weather risk scoring with dynamic rerouting
- 📢 **Gamified incident reporting** — trust-score-based validation and leaderboard
- 🔊 **Voice navigation** — hands-free turn-by-turn guidance via Android TTS
- 🔋 **Battery optimization** — adaptive GPS update intervals

---

## 🧠 ML Model — STGAT-FusionNet

A novel hybrid deep learning architecture combining:

| Component | Role |
|---|---|
| GATv2 (Graph Attention Network) | Spatial encoder — models road network graph |
| Temporal Fusion Transformer (TFT) | Temporal encoder — captures traffic patterns |
| BiLSTM with Attention | Sequential encoder — bidirectional traffic flow |
| Multi-Head Attention Fusion | Combines all three encoder outputs |
| HistGradientBoosting Meta-Learner | Ensemble refinement for final prediction |

- ⚡ Inference latency: **0.31 ms/sample**
- 🚀 End-to-end API response: **< 100 ms**

---

## 🛠️ Tech Stack

### Android
- Kotlin / Java — Android SDK 34
- Google Maps SDK + Directions API
- Firebase Auth + Firestore
- Android TTS (Text-to-Speech)

### Backend
- FastAPI (Python) on Google Cloud Run
- Firebase Firestore (9 collections)
- HMAC-SHA256 request authentication
- Rate limiting (100 req/min per user)

### ML Platform
- PyTorch — STGAT-FusionNet model
- Scikit-learn — HistGradientBoosting meta-learner
- NetworkX — road graph construction
- Optuna — hyperparameter optimization

### External APIs
- Google Maps Directions API
- Open-Meteo Forecast API (weather)
- Firebase Authentication
- Google Cloud Run (serverless deployment)

---

## 📊 Dataset

- 60,000 rows, 13 raw features
- 666 unique road segments
- Simulates 9 major Indian cities
- Time range: January–March 2025
- 40 engineered features after preprocessing

---

## 🧪 Testing

| Category | Framework | Tests | Result |
|---|---|---|---|
| Python Backend + ML | PyTest | 15 | ✅ 15 Passed |
| Kotlin Core Engine | JUnit 4 | 13 | ✅ 13 Passed |
| Android Unit Tests | JUnit 4 | 12 | ✅ 12 Passed |
| Android Instrumented | Espresso | 2 | ✅ 2 Passed |
| **Total** | | **42** | **✅ 42 Passed** |

---

## ⚙️ Setup Instructions

### Prerequisites
- Android Studio (latest)
- Python 3.10+
- Google Cloud account
- Firebase project

### Android App
```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/smart-traffic-app.git

# Open in Android Studio
# Add your google-services.json to app/
# Build and run on device/emulator (API 24+)
```

### Backend
```bash
cd backend_platform
pip install -r requirements.txt

# Add your .env file with:
# FIREBASE_CREDENTIALS=...
# GOOGLE_MAPS_API_KEY=...

uvicorn app:app --reload
```

### ML Platform
```bash
cd ml_platform
pip install -r requirements.txt

# Train the model
python train.py

# Start inference server
python inference_server.py
```

---
