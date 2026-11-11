#!/bin/sh
set -eu

mkdir -p /logs

exec java -cp bin ImageRestorationServer >> /logs/application.log 2>&1
