#!/bin/bash
source /opt/conda/etc/profile.d/conda.sh

set -a
source .env
set +a

conda activate $CONDA_ENV_NAME

EXIT_CODE=0

CLEANED=false
cleanup() {
    if [ "$CLEANED" = true ]; then return; fi
    CLEANED=true
    echo "Shutting down API and database..."
    kill $CONTROL_PID 2>/dev/null
    wait $CONTROL_PID 2>/dev/null
    pg_ctl -D $DB_DATA_DIR stop
    exit $EXIT_CODE
}

trap cleanup EXIT SIGINT SIGTERM SIGHUP

pg_ctl -D $DB_DATA_DIR -l $DB_DATA_DIR/server.log start

echo "Starting PostgreSQL API on port $API_PORT..."
python -u -m gunicorn main:app \
  -k uvicorn.workers.UvicornWorker \
  --workers 1 \
  --bind 0.0.0.0:$API_PORT &

CONTROL_PID=$!
wait $CONTROL_PID
EXIT_CODE=$?

exit $EXIT_CODE