#!/bin/bash
# Inicia o servidor. Variáveis opcionais: PORT (padrão 8080), THREADS (padrão = nº de CPUs)
cd "$(dirname -- "$(readlink -f -- "$0")")"
exec java -cp bin ImageRestorationServer
