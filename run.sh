#!/usr/bin/env bash

# ==============================================================================
# MessMate Backend Startup & Development Script
# ==============================================================================

set -eo pipefail

# Determine script directory so it can be executed from anywhere
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Color formatting
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

print_banner() {
    echo -e "${BLUE}===============================================${NC}"
    echo -e "${GREEN}           MessMate Spring Boot Backend        ${NC}"
    echo -e "${BLUE}===============================================${NC}"
}

print_help() {
    print_banner
    echo -e "Usage: ./run.sh [OPTIONS] [ADDITIONAL_GRADLE_ARGS...]"
    echo ""
    echo "Options:"
    echo "  -c, --clean         Clean build artifacts before running (clean bootRun)"
    echo "  -k, --kill          Free port if already occupied before starting"
    echo "  -b, --build         Build the project without starting server"
    echo "  -t, --test          Run project test suite"
    echo "  -d, --debug         Run in debug mode (enables JVM debug on port 5005)"
    echo "  -p, --port <PORT>   Override server port (default configured in application.properties: 8080)"
    echo "  -h, --help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./run.sh                    # Start server normally (port 8080)"
    echo "  ./run.sh -k                 # Kill existing process on port 8080 & start"
    echo "  ./run.sh --clean            # Clean build & start"
    echo "  ./run.sh -p 8085            # Run on custom port (e.g. 8085)"
    echo "  ./run.sh --debug            # Start with JVM debugger attached"
    echo "  ./run.sh --test             # Run tests"
}

# Load environment variables if .env exists
if [ -f "$SCRIPT_DIR/.env" ]; then
    echo -e "${GREEN}Loading environment variables from .env...${NC}"
    set -a
    source "$SCRIPT_DIR/.env"
    set +a
elif [ -f "$SCRIPT_DIR/../.env" ]; then
    echo -e "${GREEN}Loading environment variables from root .env...${NC}"
    set -a
    source "$SCRIPT_DIR/../.env"
    set +a
fi

# Ensure Gradle wrapper is executable
if [ ! -x "./gradlew" ]; then
    echo -e "${YELLOW}Making gradlew executable...${NC}"
    chmod +x ./gradlew
fi

# Parse options
ACTION="run"
CLEAN=false
DEBUG=false
KILL_OCCUPIED=false
CUSTOM_PORT=""
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            print_help
            exit 0
            ;;
        -c|--clean)
            CLEAN=true
            shift
            ;;
        -k|--kill)
            KILL_OCCUPIED=true
            shift
            ;;
        -b|--build)
            ACTION="build"
            shift
            ;;
        -t|--test)
            ACTION="test"
            shift
            ;;
        -d|--debug)
            DEBUG=true
            shift
            ;;
        -p|--port)
            if [[ -n "${2:-}" ]]; then
                CUSTOM_PORT="$2"
                shift 2
            else
                echo -e "${RED}Error: --port requires a port number.${NC}"
                exit 1
            fi
            ;;
        --port=*)
            CUSTOM_PORT="${1#*=}"
            shift
            ;;
        *)
            EXTRA_ARGS+=("$1")
            shift
            ;;
    esac
done

print_banner

# Execute actions
if [ "$ACTION" = "build" ]; then
    echo -e "${BLUE}Building MessMate Backend...${NC}"
    if [ "$CLEAN" = true ]; then
        ./gradlew clean build "${EXTRA_ARGS[@]}"
    else
        ./gradlew build "${EXTRA_ARGS[@]}"
    fi
    echo -e "${GREEN}Build completed successfully!${NC}"
    exit 0
fi

if [ "$ACTION" = "test" ]; then
    echo -e "${BLUE}Running Test Suite...${NC}"
    if [ "$CLEAN" = true ]; then
        ./gradlew clean test "${EXTRA_ARGS[@]}"
    else
        ./gradlew test "${EXTRA_ARGS[@]}"
    fi
    exit 0
fi

# Determine target port
TARGET_PORT="${CUSTOM_PORT:-8080}"

# Check and free port if requested or occupied
PORT_PID=$(lsof -ti :"$TARGET_PORT" 2>/dev/null || true)
if [ -n "$PORT_PID" ]; then
    if [ "$KILL_OCCUPIED" = true ]; then
        echo -e "${YELLOW}Freeing occupied port ${TARGET_PORT} (PID: ${PORT_PID})...${NC}"
        kill -9 $PORT_PID 2>/dev/null || true
        sleep 1
    else
        echo -e "${YELLOW}Warning: Port ${TARGET_PORT} is currently in use by PID ${PORT_PID}.${NC}"
        echo -e "${YELLOW}Tip: Use './run.sh -k' to automatically kill the occupying process.${NC}"
    fi
fi

# Start application
GRADLE_TASKS=()
if [ "$CLEAN" = true ]; then
    GRADLE_TASKS+=("clean")
fi
GRADLE_TASKS+=("bootRun")

BOOTRUN_ARGS=()
if [ "$DEBUG" = true ]; then
    echo -e "${YELLOW}Debug mode enabled (JVM debug listening on port 5005)${NC}"
    BOOTRUN_ARGS+=("--debug-jvm")
fi

if [ -n "$CUSTOM_PORT" ]; then
    echo -e "${GREEN}Overriding server port to: ${CUSTOM_PORT}${NC}"
    BOOTRUN_ARGS+=("--args=--server.port=${CUSTOM_PORT}")
fi

echo -e "${GREEN}Starting Spring Boot Application on port ${TARGET_PORT}...${NC}"
echo -e "${BLUE}Press Ctrl+C to stop.${NC}"
echo ""

./gradlew "${GRADLE_TASKS[@]}" "${BOOTRUN_ARGS[@]}" "${EXTRA_ARGS[@]}"
