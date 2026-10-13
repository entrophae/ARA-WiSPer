#!/bin/bash
# 0. export .env variables
export $(grep -v '^#' .env | xargs)

# 1. Manually load the Conda configuration into the current sub-shell
# replaces the need for 'conda init' and 'source ~/.bashrc' when used outside of script
source /opt/conda/etc/profile.d/conda.sh

# 2. Setup Conda Environment
# --solver=classic bypasses the use of the libmamba plugin
# Although Conda was configured to use it, it wasn't installed on the system
if ! conda info --envs | grep -q "$CONDA_ENV_NAME"; then
    echo "Creating isolated PostgreSQL environment ($CONDA_ENV_NAME)..."
    conda env create -f environment.yml -n $CONDA_ENV_NAME --solver=classic
fi

# 3. Extract user and check if it's empty
CURRENT_ENV_USER=$(grep "^POSTGRES_USER=" .env | cut -d '=' -f2- | tr -d " \"'")

if [ -z "$CURRENT_ENV_USER" ]; then
    # Check if the variable actually exists in the file (even if blank)
    if grep -q "^POSTGRES_USER=" .env; then
        # Edit it exactly where it was found
        sed -i "s/^POSTGRES_USER=.*/POSTGRES_USER=\"$(whoami)\"/" .env
    else
        # If the variable doesn't exist at all, append it to the bottom
        echo "POSTGRES_USER=\"$(whoami)\"" >> .env
    fi
    echo "✅ Dynamically set POSTGRES_USER=\"$(whoami)\" in .env"
else
    echo "⚡ POSTGRES_USER is already properly set to: $CURRENT_ENV_USER"
fi

# 4. Activate the environment
echo "Activating environment..."
conda activate $CONDA_ENV_NAME
echo "Environment active."

# 5. Initialize the Database Cluster
if [ ! -d "$DB_DATA_DIR" ]; then
    echo "Initializing database cluster in $DB_DATA_DIR..."
    initdb -D $DB_DATA_DIR
else
    echo "Database cluster found."
fi

# 6. Ensure the database exists
if ! pg_ctl -D $DB_DATA_DIR status > /dev/null 2>&1; then
    pg_ctl -D $DB_DATA_DIR -l $DB_DATA_DIR/server.log start
fi

# 7. Create the database
if ! psql -lqt | cut -d \| -f 1 | grep -qw "$POSTGRES_DBNAME"; then
    echo "Creating '$POSTGRES_DBNAME'..."
    createdb $POSTGRES_DBNAME
fi

# 8. Shut down to force initiate.sh as server start, enforcing the existence of the shield api
pg_ctl -D $DB_DATA_DIR stop

echo "------------------------------------------------"
echo "PostgreSQL Vault is ready!"
echo "Run './initiate.sh' to start the server."
echo "------------------------------------------------"
