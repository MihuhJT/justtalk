#!/bin/zsh
# Enables Russian in Gboard on running Android emulators (settings are lost on restart
# when the AVD runs with -read-only, so run this after each emulator start).
# NOTE: Gboard drops the Russian subtype again unless Russian is also in system languages
# (Settings > System > Languages > Add a language > Русский, keep English first). Do that once per emulator,
# then run this script / pick Русский via long-press on space or the US/РУ button of the Gboard toolbar.
# Usage: emu-russian.sh [emulator-5554 emulator-5556 ...]   (default: all running emulators)
ADB=~/Library/Android/sdk/platform-tools/adb
GB=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
VOICE=com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService
EN_HASH=1594443099      # English (US)
RU_HASH=-988987337      # Russian (ru_RU) in Gboard

devs=("$@")
[[ ${#devs} -eq 0 ]] && devs=(${(f)"$($ADB devices | awk '/^emulator-.*device$/{print $1}')"})

for d in $devs; do
  echo "== $d"
  $ADB -s $d shell "settings put secure enabled_input_methods '$GB;$EN_HASH;$RU_HASH:$VOICE'"
  $ADB -s $d shell settings put secure default_input_method $GB
  $ADB -s $d shell settings put secure show_ime_with_hard_keyboard 1
  $ADB -s $d shell settings get secure enabled_input_methods | cut -c1-110
done
echo "Switch layout: Ctrl+Space (or Cmd+Space / Ctrl+Shift if Mac intercepts it)."
