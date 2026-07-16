#!/bin/bash
echo Starting
if [ ! -d "venv" ]; then
    python3 -m venv venv
    init= true
fi

echo entering venv

source venv/bin/activate

if [ "$init" = true ]; then
    echo checking requirements
    pip install -r requirements.txt
fi

echo starting service

python -m gunicorn main:app \
  -k uvicorn.workers.UvicornWorker \
  --workers 1 \
  --bind 0.0.0.0:8577
