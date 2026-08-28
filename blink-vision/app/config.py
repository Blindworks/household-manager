"""Konfiguration aus Umgebungsvariablen."""
import os

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
API_TOKEN = os.environ.get("API_TOKEN", "")
CAMERA_NAME = os.environ.get("BLINK_CAMERA_NAME", "")   # leer = erste gefundene Kamera
POLL_SECONDS = int(os.environ.get("POLL_SECONDS", "10"))
# Eigener, langsamerer Takt fuer den Bewegungs-Waechter: er liest nur Metadaten
# und muss nicht so eng laufen wie die zeitkritische Gesichtserkennung.
MOTION_POLL_SECONDS = int(os.environ.get("MOTION_POLL_SECONDS", "30"))
HEARTBEAT_SECONDS = int(os.environ.get("HEARTBEAT_SECONDS", "60"))
CONFIDENCE_THRESHOLD = float(os.environ.get("CONFIDENCE_THRESHOLD", "0.5"))
COOLDOWN_SECONDS = int(os.environ.get("COOLDOWN_SECONDS", "120"))
DATA_DIR = os.environ.get("DATA_DIR", "./data")
PORT = int(os.environ.get("PORT", "8090"))
