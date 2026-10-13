#!/bin/bash
# Compila a aplicação para a pasta bin/
set -e
cd "$(dirname -- "$(readlink -f -- "$0")")"
javac -d bin src/*.java
