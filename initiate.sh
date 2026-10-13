#!/bin/bash
source /opt/conda/etc/profile.d/conda.sh
export $(grep -v '^#' .env | xargs)

conda activate $CONDA_ENV_NAME

EXIT_CODE=0

cleanup() {
    echo "shutting down api and database..."
    pg_ctl -D $DB_DATA_DIR stop
    exit $EXIT_CODE
}

trap 'cleanup' SIGINT SIGTERM

pg_ctl -D $DB_DATA_DIR -l $DB_DATA_DIR/server.log start

echo starting service

python -u -m gunicorn main:app \
  -k uvicorn.workers.UvicornWorker \
  --workers 1 \
  --bind 0.0.0.0:$API_PORT &

CONTROL_PID=$!
wait $CONTROL_PID
EXIT_CODE=$?

exit $EXIT_CODE