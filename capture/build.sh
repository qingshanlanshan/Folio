#!/bin/sh
# Builds bin/folio-capture with the privacy strings embedded (needed for the permission prompts).
set -e
cd "$(dirname "$0")"
mkdir -p ../bin
swiftc -O -o ../bin/folio-capture main.swift \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker Info.plist
codesign --force --sign - --identifier com.qshlab.folio.capture ../bin/folio-capture
echo "built bin/folio-capture"
