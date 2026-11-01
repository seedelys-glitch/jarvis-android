#!/bin/bash
# Essai complet sur téléphone virtuel : installe l'appli, la lance, puis lui « fait écouter » des phrases
# enregistrées (tests/audio) à la place du micro, et vérifie dans le journal Android ce qu'elle a compris et fait.
set -x
P=fr.jabran.jarvis
APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r -g $APK
adb shell pm grant $P android.permission.RECORD_AUDIO || true
adb shell pm grant $P android.permission.POST_NOTIFICATIONS || true
adb shell appops set $P SYSTEM_ALERT_WINDOW allow || true
adb shell settings put secure enabled_accessibility_services $P/$P.MainsLibres || true
adb shell settings put secure accessibility_enabled 1 || true
adb shell input keyevent 224; adb shell wm dismiss-keyguard; adb shell input keyevent 82; sleep 2
adb shell settings put system screen_off_timeout 1800000
adb logcat -c
adb shell am start -n $P/.MainActivity
sleep 40   # copie des modèles + chargement

joue() {   # $1 = fichier, $2 = secondes d'attente
  echo "===== ESSAI $1"
  adb push tests/audio/$1.wav /data/local/tmp/essai.wav >/dev/null
  adb shell run-as $P cp /data/local/tmp/essai.wav files/essai-audio.wav
  sleep $2
}
joue 1-heure 9
joue 2-merci 12
sleep 14
joue 7-memoire 25
joue 8-rappel 20
joue 3-ouvre-chrome 20
adb shell dumpsys activity activities | grep -m1 "topResumedActivity\|mResumedActivity"
joue 4-ferme-chrome 20
adb shell dumpsys activity activities | grep -m1 "topResumedActivity\|mResumedActivity"
sleep 14
joue 5-sans-jarvis 25
joue 6-hey-anglais 20

adb shell pidof $P && echo "APPLI VIVANTE" || echo "APPLI MORTE"
adb logcat -d > logcat.txt
grep -E "Rappel|JarvisReveil|JarvisPage|JarvisApps|AndroidRuntime|FATAL|sherpa-onnx.*(error|Error)|$P.*has died" logcat.txt > logcat-resume.txt
cat logcat-resume.txt
true
