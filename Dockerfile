FROM python:3.13-slim

ARG WITH_BROWSER=1
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    SLIDEMILL_DATA_DIR=/data PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg fonts-wqy-zenhei curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
 && if [ "$WITH_BROWSER" = "1" ]; then python -m playwright install --with-deps chromium; fi

COPY app ./app
COPY static ./static
COPY fonts ./fonts

RUN useradd --system --uid 10001 --home /app slidemill && mkdir -p /data && chown slidemill /data
USER slidemill
VOLUME /data
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD curl -fsS http://127.0.0.1:8000/healthz || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
