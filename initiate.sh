#!/bin/bash

# Add existing CUDA libraries to the environment
export LD_LIBRARY_PATH="$(find /opt/conda/lib/python3.12/site-packages/nvidia -type d -name lib | tr '\n' ':')$LD_LIBRARY_PATH"
export OMP_NUM_THREADS=1
export MKL_NUM_THREADS=1

echo "Starting STT..."
if [ ! -d "venv" ]; then
    python3 -m venv venv
fi

echo "Entering venv..."
source venv/bin/activate

if ! command -v gunicorn &> /dev/null; then
    echo "Dependencies missing. Installing from requirements.txt..."
    pip install -r requirements.txt
fi

set -a
source .env
set +a

echo "Starting STT service on port $PORT..."
exec python -u -m gunicorn main:app \
  -k uvicorn.workers.UvicornWorker \
  --workers 1 \
  --bind 0.0.0.0:$PORT