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
    echo "creating isolated RAG environment ($CONDA_ENV_NAME)..."
    conda env create -f environment.yml -n $CONDA_ENV_NAME --solver=classic
fi

# 3. Setup for database usage: Extract user and check if it's empty
CURRENT_ENV_USER=$(grep "^POSTGRES_USER=" .env | cut -d '=' -f2- | tr -d " \"'")
if [ -z "$CURRENT_ENV_USER" ]; then
    if grep -q "^POSTGRES_USER=" .env; then
        sed -i "s/^POSTGRES_USER=.*/POSTGRES_USER=\"$(whoami)\"/" .env
    else
        echo "POSTGRES_USER=\"$(whoami)\"" >> .env
    fi
    echo "✅ Dynamically set POSTGRES_USER=\"$(whoami)\" in .env"
else
    echo "⚡ POSTGRES_USER is already properly set to: $CURRENT_ENV_USER"
fi

echo "------------------------------------------------"
echo "RAG Service is ready!"
echo "run './initiate.sh' to start the server."
echo "------------------------------------------------"