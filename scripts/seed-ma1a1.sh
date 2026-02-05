#!/bin/bash
# Script to seed MA1A1 room data into the beworking database
# Usage: ./seed-ma1a1.sh [host] [port] [database] [user]
#
# Default values assume docker-compose setup:
#   host: localhost
#   port: 5433
#   database: mydatabase
#   user: postgres

HOST=${1:-localhost}
PORT=${2:-5433}
DATABASE=${3:-mydatabase}
USER=${4:-postgres}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/../src/main/resources/db/V001__seed_ma1a1_room.sql"

if [ ! -f "$SQL_FILE" ]; then
    echo "Error: SQL file not found at $SQL_FILE"
    exit 1
fi

echo "Seeding MA1A1 room data..."
echo "Host: $HOST:$PORT, Database: $DATABASE, User: $USER"
echo ""

PGPASSWORD=postgres psql -h "$HOST" -p "$PORT" -U "$USER" -d "$DATABASE" -f "$SQL_FILE"

if [ $? -eq 0 ]; then
    echo ""
    echo "✓ MA1A1 room data seeded successfully!"
    echo ""
    echo "Verify the data with:"
    echo "  PGPASSWORD=postgres psql -h $HOST -p $PORT -U $USER -d $DATABASE -c \"SELECT id, code, name, hero_image FROM beworking.rooms WHERE code='MA1A1';\""
else
    echo ""
    echo "✗ Failed to seed data. Check database connection and schema."
    exit 1
fi
