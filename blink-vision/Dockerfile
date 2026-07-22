FROM python:3.12-slim

WORKDIR /app

# insightface zieht das NICHT-headless opencv-python mit (pip show insightface ->
# Requires: ... opencv-python), deshalb wird libGL.so.1 gebraucht - der Hinweis
# "headless braucht kein libgl" traegt hier nicht. libglib2.0-0 fuer OpenCV,
# libgomp1 fuer die OpenMP-Threads von onnxruntime.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Modell buffalo_s (~124 MB) beim Build vorladen: sonst laedt es der erste
# Containerstart aus dem Netz und der erste Clip laeuft in einen Timeout.
# Der Cache liegt in $HOME/.insightface - Build und Laufzeit laufen beide als
# root, der Pfad ist also zur Laufzeit derselbe. Das Zip-Archiv nach dem
# Entpacken loeschen, es wird nur beim Download gebraucht.
RUN python -c "from insightface.app import FaceAnalysis; \
FaceAnalysis(name='buffalo_s', allowed_modules=['detection', 'recognition'], \
providers=['CPUExecutionProvider']).prepare(ctx_id=-1, det_size=(640, 640))" \
    && rm -f /root/.insightface/models/*.zip

COPY app/ app/

ENV DATA_DIR=/data
VOLUME /data

EXPOSE 8090
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
