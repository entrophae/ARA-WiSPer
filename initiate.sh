#!/bin/bash

# add existing CUDA libraries to the environment
export LD_LIBRARY_PATH="$(find /opt/conda/lib/python3.12/site-packages/nvidia -type d -name lib | tr '\n' ':')$LD_LIBRARY_PATH"

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

EXIT_CODE=0

cleanup() {
    echo "shutting down service..."
    kill $CONTROL_PID 2>/dev/null
    exit $EXIT_CODE
}

trap 'cleanup' SIGINT SIGTERM

python -u -m gunicorn main:app \
  -k uvicorn.workers.UvicornWorker \
  --workers 1 \
  --bind 0.0.0.0:$PORT &

CONTROL_PID=$!
wait $CONTROL_PID
EXIT_CODE=$?

exit $EXIT_CODE