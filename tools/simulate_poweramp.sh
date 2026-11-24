#!/system/bin/sh
# Simulates Poweramp broadcasts so the widget can be tested on a device/emulator without Poweramp.
#   adb push tools/simulate_poweramp.sh /data/local/tmp/sim.sh
#   adb shell sh /data/local/tmp/sim.sh [pos_seconds] [paused:true|false] [rating 0-5] [shuffle] [repeat]
POS=${1:-0}
PAUSED=${2:-true}
RATING=${3:-0}
SHUFFLE=${4:-0}
REPEAT=${5:-0}
NOW=$(date +%s)000
R=com.sparkle.pawidget/.PowerampEventReceiver
am broadcast -n $R -a com.maxmpz.audioplayer.TRACK_CHANGED_EXPLICIT \
  --es title 'Get It All' --es artist 'Surya Hearts' --es album test --es path /music/get_it_all.flac \
  --el realId 7 --ei durMs 150000 --ei pos $POS --el ts $NOW \
  --es codec FLAC --ei fileType 1 --ei sampleRate 96000 --ei bitsPerSample 24 --ei rating $RATING --ei posInList 1 --ei listSize 20 --eu catUri content://com.maxmpz.audioplayer.data/folders/1/files > /dev/null
am broadcast -n $R -a com.maxmpz.audioplayer.STATUS_CHANGED_EXPLICIT \
  --ez paused $PAUSED --ei state 1 --ei pos $POS --el ts $NOW > /dev/null
# Mode has no explicit variant: reaches the app only while its process is alive (dynamic receiver).
am broadcast -a com.maxmpz.audioplayer.PLAYING_MODE_CHANGED --ei shuffle $SHUFFLE --ei repeat $REPEAT > /dev/null
echo "sent pos=$POS paused=$PAUSED rating=$RATING shuffle=$SHUFFLE repeat=$REPEAT"
