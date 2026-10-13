FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Hugging Face Spaces routes traffic to port 7860 and keeps /data between
# restarts when persistent storage is switched on (Settings → Storage).
ENV PORT=7860 RCA_DATA_DIR=/data
RUN mkdir -p /data && chmod 777 /data
EXPOSE 7860

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "7860"]
