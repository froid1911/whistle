#!/bin/sh
# Fake herdr: logs argv to $FAKE_ARGS, prints two frames, copies stdin lines to $FAKE_STDIN.
echo "$@" > "$FAKE_ARGS"
echo '{"type":"terminal.frame","seq":1,"width":80,"height":24,"full":true,"bytes":"aGVsbG8="}'
echo '{"bytes":"d29ybGQ=","seq":2}'
echo '{"type":"weird.unknown"}'
if [ -n "$FAKE_EXIT" ]; then exit 0; fi
while IFS= read -r line; do
  printf "%s\n" "$line" >> "$FAKE_STDIN"
  case "$line" in *terminal.release*) exit 0;; esac
done
