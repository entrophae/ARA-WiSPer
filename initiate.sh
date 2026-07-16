#!/bin/bash
echo Starting
if [ ! -d "venv" ]; then
    python3 -m venv venv
    init= true
fi

echo entering venv

source venv/bin/activate

if ! command -v gunicorn &> /dev/null; then
    echo "Dependencies missing. Installing from requirements.txt..."
    pip install -r requirements.txt
fi

echo starting service

export $(grep -v '^#' .env | xargs)

python -m gunicorn main:app \
  -k uvicorn.workers.UvicornWorker \
  --workers 1 \
  --bind 0.0.0.0:$PORT
