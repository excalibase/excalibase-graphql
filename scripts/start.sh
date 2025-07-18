#!/bin/bash

# Simple start command for Excalibase GraphQL E2E Testing
# This is the equivalent of "npm start" but for our GraphQL project

echo "🚀 Starting Excalibase GraphQL E2E Testing..."
echo ""

# Check if we're in the project root
if [ ! -f "docker-compose.yml" ]; then
    echo "❌ Error: Please run this from the project root directory"
    exit 1
fi

# Run the full e2e test suite
./scripts/start-e2e.sh "$@" 