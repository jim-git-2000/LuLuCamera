FROM python:3.12-slim
ARG GPU=false
WORKDIR /app/backend
COPY backend/pyproject.toml ./
COPY backend/lulucamera_backend ./lulucamera_backend
RUN if [ "$GPU" = "true" ]; then pip install --no-cache-dir '.[gpu]'; else pip install --no-cache-dir .; fi
ENV PYTHONUNBUFFERED=1 LULU_STORAGE_DIR=/data LULU_ASSET_DIR=/assets/characters HF_HOME=/models
EXPOSE 8000
CMD ["uvicorn", "lulucamera_backend.api:app", "--host", "0.0.0.0", "--port", "8000"]
