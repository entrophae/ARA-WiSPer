#!/bin/bash
source venv/bin/activate

python -m gunicorn main:app \
  -k uvicorn.workers.UvicornWorker \
  --workers 1 \
  --bind 0.0.0.0:8000