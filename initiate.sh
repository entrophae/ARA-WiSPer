#!/bin/bash
source /opt/conda/etc/profile.d/conda.sh
export $(grep -v '^#' .env | xargs)

conda activate $CONDA_ENV_NAME

EXIT_CODE=0

cleanup() {
    echo "shutting down service..."
    kill $CONTROL_PID 2>/dev/null
    exit $EXIT_CODE
}

trap 'cleanup' SIGINT SIGTERM

echo "starting rag api..."

python -u -m gunicorn api:app \
  -k uvicorn.workers.UvicornWorker \
  --workers 1 \
  --bind 0.0.0.0:$PORT &
  
CONTROL_PID=$!
wait $CONTROL_PID
EXIT_CODE=$?

exit $EXIT_CODE